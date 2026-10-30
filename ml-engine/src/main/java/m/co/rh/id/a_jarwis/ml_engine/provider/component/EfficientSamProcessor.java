package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import m.co.rh.id.alogger.ILogger;

/**
 * Processor of the EfficientSAM promptable object segmentation model.
 * Produces the RAW binary mask (CV_8U, 0..255, 255 = object) of the prompted
 * object at the model's output plane resolution (1024x1024 for this export,
 * matching the configured input — the export's decoder emits this fixed
 * 1024² plane even at a smaller input, see the model contract paragraph): the caller
 * marks the object with
 * a bounding box prompt plus a few foreground point prompts (the magic eraser
 * engine builds one prompt set per connected painted stroke group, see
 * {@link MagicEraserEngine#computeObjectMask}, because SAM segments ONE object
 * per prompt set), and the model returns K candidate masks of which the one
 * with the highest predicted IOU is used. The mask is NOT feathered and NOT
 * upsampled here: the engine unions the raw masks of the prompt groups and
 * feathers/upsamples the union once.
 * <p>
 * Model contract (verified against the opencv_zoo efficientSAM.py reference):
 * three named inputs - batched_images (1,3,S,S float RGB scaled to 0..1, S is
 * the configured {@link #MODEL_INPUT_SIZE}) — the ONNX was exported with the
 * reference's 1024 dims and the input spatial dims are dynamic (a 512 input
 * is accepted, setInput/forward run and complete, verified on the 32-bit
 * test emulator) but the OUTPUT spatial dims are FIXED at 1024x1024 by the
 * export regardless of the input size (observed on device at a 512 input:
 * output_masks dims=5 sizes=[1,1,3,1024,1024] — the decoder upsample in the
 * export targets the constant reference resolution),
 * batched_point_coords (1,1,6,2 float, coordinates already in the same model
 * input space) and batched_point_labels (1,1,6,1 float; 1 = foreground point,
 * 2 = box top-left, 3 = box bottom-right, -1 = padding). Outputs are
 * output_masks (1,1,K,S,S logits, a pixel belongs to the mask when its
 * logit is &gt;= 0) and iou_predictions (1,1,K quality score per candidate).
 * The Java extraction reads the actual output blob layout instead of assuming
 * the reference 5-d shape, the DNN runtime may collapse or reorder the leading
 * singleton axes of the export (see extractBestMask).
 * The reference also filters background points (label 0) as a post-step, this
 * implementation sends no background points so that filter is a no-op and is
 * skipped. The model input resize is aspect distorting (no letterbox padding),
 * the prompt coordinates are scaled the same way.
 * <p>
 * The 512 input (down from the exported 1024 reference size) was attempted to
 * relax the 32-bit memory pressure (the cv::dnn workspace OpenCV retains
 * inside the net at 1024, ~591MB with a ~192MB largest contiguous request,
 * cannot be re-created after address-space fragmentation — the detect right
 * after an erase failed with "Failed to allocate 201326592 bytes in
 * dnn::forward"). VALIDATED ON DEVICE (32-bit x86 emulator, api24): the 512
 * input runs (~3.3-4.1s per forward vs the 6.9s x86_64 baseline at 1024,
 * ~2x faster) and the extraction handles the fixed 1024² output (see the
 * model contract paragraph and extractBestMask) — BUT the retained native
 * footprint does NOT shrink (604MB retained after one forward — the 607MB
 * reading is the re-detect figure, same order — vs ~591MB at
 * the 1024 input; release+gc reclaims to ~15MB and a re-detect on the
 * recreated net works), and mask QUALITY of smaller objects degrades
 * materially: a 200px square (19.5% of the image width) came back only
 * ~76% covered by the mask (the model's own iou_predictions still claims
 * 0.78-0.82 — overconfident), so LaMa reconstructs the un-erased rim and the
 * two-square erase test fails. FINAL VERDICT: NO-GO, the 1024 input stays —
 * the completed experiment measured NO memory improvement (604MB retained
 * after a forward vs ~591MB at the 1024 input: the decoder-side fixed 1024²
 * buffers dominate the footprint, the smaller encoder input does not shrink
 * them) on top of the materially degraded small-object masks (24% coverage
 * loss on a ~20%-width object → erase failure). This documentation records
 * why the 512 experiment is not coming back.
 * <p>
 * A processor instance must never have its
 * {@link #computeRawMask(Bitmap, float[][], float[], String)}
 * invoked concurrently, callers are expected to serialize their calls on one
 * instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache recreates a fresh
 * processor on demand.
 */
class EfficientSamProcessor {
    private static final String TAG = "EfficientSamProcessor";
    /**
     * The model INPUT resolution, the model is trained on an aspect
     * distorting resize at this size (no letterbox padding), so the aspect
     * distortion here matches the reference preprocessing and no padding is
     * added. The exported reference 1024 size: the temporary 512 experiment
     * was reverted after it measured NO retained-footprint improvement and
     * materially degraded the masks of smaller objects (see the class
     * javadoc). The export's input dims are dynamic (a smaller input also
     * runs — see the class javadoc) and its output dims are FIXED at
     * 1024x1024 with content tied to the INPUT pixel grid — only
     * {@link #extractBestMask} cares (it derives the plane from the output
     * blob and crops the content region when a smaller input is fed); this
     * constant is the input/prompt space only.
     */
    private static final int MODEL_INPUT_SIZE = 1024;
    /**
     * Plausible bounds of the square logit-plane side the plane locator of
     * {@link #extractBestMask} accepts from the output blob: SAM-family
     * exports emit power-of-two square planes (256/512/1024 are the
     * resolutions of this model family; 1024x1024 is measured on device at
     * the 512 input), bounded defensively against pathological blobs
     */
    private static final int MIN_PLANE_SIDE = 64;
    private static final int MAX_PLANE_SIDE = 4096;
    /**
     * The model accepts at most 6 prompt points per call (the ONNX input is a
     * fixed (1,1,6,2)/(1,1,6,1) tensor), unused slots are padded with
     * coordinates 0,0 and label -1
     */
    private static final int MAX_PROMPT_POINTS = 6;
    /**
     * Name of the image input tensor (see the model contract in the class javadoc)
     */
    private static final String INPUT_IMAGES = "batched_images";
    /**
     * Name of the prompt coordinates input tensor (see the model contract in the class javadoc)
     */
    private static final String INPUT_POINT_COORDS = "batched_point_coords";
    /**
     * Name of the prompt labels input tensor (see the model contract in the class javadoc)
     */
    private static final String INPUT_POINT_LABELS = "batched_point_labels";
    /**
     * Mask candidate output tensor: (1,1,K,N,N) raw logits at the export's
     * output plane (N is fixed 1024 in this export regardless of the
     * configured input size, see the class javadoc)
     */
    private static final String OUTPUT_MASKS = "output_masks";
    /**
     * Per candidate quality output tensor: (1,1,K)
     */
    private static final String OUTPUT_IOU = "iou_predictions";
    /**
     * Logit threshold separating the mask from the background: a pixel belongs
     * to the mask when its logit is &gt;= 0 (equivalent to sigmoid &gt;= 0.5)
     */
    private static final float MASK_LOGIT_THRESHOLD = 0f;
    /**
     * Mask pixel value for the masked (inside) region
     */
    private static final int MASK_VALUE = 255;
    /**
     * Compile-time gate of the spike diagnostics (see {@link #spikeLog()}):
     * false in committed code, flipped to true only temporarily for a
     * connectedCheck spike run
     */
    private static final boolean SPIKE_LOG = false;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public EfficientSamProcessor(String modelPath, ILogger logger) {
        mModelNet = Dnn.readNetFromONNX(modelPath);
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
    }

    /**
     * Release the native network of this processor: the parsed model weights
     * plus the large dnn workspace OpenCV retains inside the
     * net after the forward() calls (measured on the 32-bit test emulator:
     * ~604MB native allocated after one forward at the 512 input, ~591MB at
     * the 1024 input — the input reduction does NOT shrink the retained
     * footprint; the release+gc reclaim returns the process to ~15MB and a
     * re-detect on the recreated net works). The
     * OpenCV 5.0.0.1 Java bindings expose
     * no public native delete for cv::dnn::Net ({@code Net.finalizeNet()}
     * only prepares the inference session, it frees nothing), the native
     * peer is deleted by the net wrapper's finalizer once this processor
     * drops its reference — {@link MLEngineInstance} nudges the runtime right
     * after releasing so the reclamation happens at that predictable point
     * instead of an arbitrary later GC.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache recreates a fresh processor
     * on demand (the model file is re-parsed, a seconds-long operation).
     * Releasing more than once is a safe no-op. Synchronized on the same
     * monitor as {@link #computeRawMask(Bitmap, float[][], float[], String)},
     * so a release can never run concurrently with an inference (that would
     * be a native use-after-free when a page dispose races an in-flight
     * task).
     */
    public synchronized void release() {
        if (mModelNet == null) {
            return;
        }
        // this was the last strong reference to the net wrapper
        mModelNet = null;
    }

    /**
     * A released processor holds no net anymore: using it would be a bug of
     * the caller (a released instance must be discarded, the engine instance
     * recreates it on demand), fail fast with a clear message instead of a
     * null native pointer deep inside the inference
     */
    private void checkNotReleased() {
        if (mModelNet == null) {
            throw new IllegalStateException(TAG
                    + " already released, a released processor must be"
                    + " discarded (MLEngineInstance recreates it on demand)");
        }
    }

    /**
     * Compute the RAW binary mask of the prompted object in the given bitmap:
     * CV_8U at the model's output plane (1024x1024 for this export, fixed
     * even at a smaller input — see the class javadoc), 255 = object,
     * thresholded at
     * logit &gt;= 0, NOT feathered and NOT upsampled — the magic eraser engine
     * unions the raw masks of the prompt groups and feathers/upsamples the
     * union once (see {@link MagicEraserEngine#computeObjectMask}).
     *
     * @param input        bitmap to be segmented, never recycled by this method
     * @param promptPoints prompt coordinates in the model input space
     *                     ({@link #MODEL_INPUT_SIZE}),
     *                     [N][2] (x,y), N must not exceed
     *                     {@link #MAX_PROMPT_POINTS}
     * @param promptLabels prompt label per point (1 = foreground point,
     *                     2 = box top-left, 3 = box bottom-right),
     *                     same length as promptPoints
     * @param logPrefix    prefix of the SPIKE diagnostic lines of this run,
     *                     the engine tags each prompt group so the per-run
     *                     logs stay readable
     * @return the native mask Mat (CV_8U single channel) at the model
     * resolution, the caller owns it and must release() it
     */
    synchronized Mat computeRawMask(Bitmap input, float[][] promptPoints, float[] promptLabels,
                                    String logPrefix) {
        checkNotReleased();
        Mat rgba = null;
        Mat rgb = null;
        Mat imageBlob = null;
        Mat pointsBlob = null;
        Mat labelsBlob = null;
        Mat mask = null;
        try {
            // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5 behavior)
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            // aspect distorting resize to the model input size (see MODEL_INPUT_SIZE)
            Imgproc.resize(rgb, rgb, new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    0, 0, Imgproc.INTER_LINEAR);
            // the input is already RGB (converted above), no swapRB in blobFromImage
            imageBlob = Dnn.blobFromImage(rgb, 1.0 / 255.0,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    new Scalar(0, 0, 0), false, false);
            pointsBlob = buildPromptCoordsBlob(promptPoints);
            labelsBlob = buildPromptLabelsBlob(promptLabels);
            mLogger.d(mTag, "preProcess_imageBlob:" + imageBlob);
            // spike diagnostics of the exact prompt tensors handed to the model
            spikeLog(logPrefix + "prompts " + promptsText(promptPoints, promptLabels));

            mModelNet.setInput(imageBlob, INPUT_IMAGES);
            mModelNet.setInput(pointsBlob, INPUT_POINT_COORDS);
            mModelNet.setInput(labelsBlob, INPUT_POINT_LABELS);
            // the return-style forward(List<String>) does not exist in the Java
            // bindings, the named outputs are read through the out-parameter form.
            // BOTH list arguments must be real java.util.ArrayList instances: the
            // OpenCV 5 JNI helper resolves java.util.ArrayList.size() by a
            // hard-coded method id and CheckJNI aborts the process on an
            // Arrays.asList view (crash found by the connectedCheck run)
            List<Mat> outputs = new ArrayList<>();
            List<String> outputNames = new ArrayList<>();
            outputNames.add(OUTPUT_MASKS);
            outputNames.add(OUTPUT_IOU);
            mModelNet.forward(outputs, outputNames);
            Mat outputMasks = null;
            Mat iouPredictions = null;
            try {
                outputMasks = outputs.get(0);
                iouPredictions = outputs.get(1);
                // log the actual output structure: the extraction adapts to it
                // (see extractBestMask) and the on-device spike run iterates
                // from these logs
                spikeLog(logPrefix + "output_masks " + sizesText(outputMasks));
                spikeLog(logPrefix + "iou_predictions " + sizesText(iouPredictions));
                mask = extractBestMask(outputMasks, iouPredictions, logPrefix);
            } finally {
                if (outputMasks != null) {
                    outputMasks.release();
                }
                if (iouPredictions != null) {
                    iouPredictions.release();
                }
            }
        } finally {
            // every Mat is released on every path: an inference failing with a
            // native "Insufficient memory" CvException must not leak the
            // (multi-megabyte) image blob
            if (rgba != null) {
                rgba.release();
            }
            if (rgb != null) {
                rgb.release();
            }
            if (imageBlob != null) {
                imageBlob.release();
            }
            if (pointsBlob != null) {
                pointsBlob.release();
            }
            if (labelsBlob != null) {
                labelsBlob.release();
            }
        }
        mLogger.d(mTag, "postProcess_mask:" + mask);
        // raw model-resolution mask: the caller (MagicEraserEngine) feathers
        // and upsamples the union of the per-group masks
        return mask;
    }

    /**
     * Build the (1,1,6,2) float32 prompt coordinates blob from the given
     * points. The tensor is kept in the documented 4-d shape for setInput,
     * but the write goes through the reshaped 1x12 2-D view sharing the same
     * memory: bulk put on n-dimensional Mats silently writes zeros in the
     * OpenCV 5.0.0.1 Java bindings (found by the spike run, the model may
     * never have received real prompts through the direct n-dim put).
     */
    private static Mat buildPromptCoordsBlob(float[][] promptPoints) {
        float[] flatCoords = new float[MAX_PROMPT_POINTS * 2];
        int count = Math.min(promptPoints.length, MAX_PROMPT_POINTS);
        for (int i = 0; i < count; i++) {
            flatCoords[i * 2] = promptPoints[i][0];
            flatCoords[i * 2 + 1] = promptPoints[i][1];
        }
        // unused slots stay 0,0 coordinates (padded together with label -1)
        Mat pointsBlob = new Mat(new int[]{1, 1, MAX_PROMPT_POINTS, 2},
                CvType.CV_32F);
        try {
            pointsBlob.reshape(1, 1).put(0, 0, flatCoords);
        } catch (RuntimeException | OutOfMemoryError e) {
            pointsBlob.release();
            throw e;
        }
        return pointsBlob;
    }

    /**
     * Build the (1,1,6,1) float32 prompt labels blob from the given labels.
     * Unused slots are padded with label -1 (see the model contract: -1 means
     * an ignored padding point). The write goes through the reshaped 1x6 2-D
     * view sharing the same memory (bulk put on n-dimensional Mats silently
     * writes zeros in the OpenCV 5.0.0.1 Java bindings, see
     * buildPromptCoordsBlob).
     */
    private static Mat buildPromptLabelsBlob(float[] promptLabels) {
        float[] flatLabels = new float[MAX_PROMPT_POINTS];
        Arrays.fill(flatLabels, -1f);
        int count = Math.min(promptLabels.length, MAX_PROMPT_POINTS);
        System.arraycopy(promptLabels, 0, flatLabels, 0, count);
        Mat labelsBlob = new Mat(new int[]{1, 1, MAX_PROMPT_POINTS, 1},
                CvType.CV_32F);
        try {
            labelsBlob.reshape(1, 1).put(0, 0, flatLabels);
        } catch (RuntimeException | OutOfMemoryError e) {
            labelsBlob.release();
            throw e;
        }
        return labelsBlob;
    }

    /**
     * Pick the candidate mask the Python reference would pick (highest
     * predicted IOU, pixels where the logit is &gt;= 0) and return it as a
     * CV_8U mask at the OUTPUT plane the blob itself carries.
     * <p>
     * The outputs are n-dimensional blobs (the reference indexes a 5-d
     * (1,1,K,N,N) mask blob and a (1,1,K) IOU blob). Bulk
     * get/put(0, 0, buffer) on n-dimensional Mats silently reads/writes zeros
     * in the OpenCV 5.0.0.1 Java bindings (found by the fp32 spike run: the
     * all-zero logits were broken blob reads, not a model mis-execution), so
     * every blob is reshaped into a 2-D view first. Reshape shares the
     * continuous blob memory without a copy: the mask blob becomes K rows of
     * planeSize elements (one proven 2-D row read per candidate) and the IOU
     * blob a single 1xK row.
     * <p>
     * The mask blob layout is still read from the blob itself instead of
     * assuming the reference 5-d shape (the DNN runtime may collapse or
     * reorder the leading singleton axes of the export). The plane side is
     * derived from the blob, NOT from {@link #MODEL_INPUT_SIZE}: the input
     * dims of this export are dynamic but the output dims are fixed —
     * measured on device at the 512 input the export still emits
     * (1,1,3,1024,1024). Locator rule: the trailing axes must form a square
     * plane of side N (either two trailing N axes or one flattened N*N
     * trailing axis) where N is a power of two within
     * {@link #MIN_PLANE_SIDE}..{@link #MAX_PLANE_SIDE} — the resolutions of
     * this model family (256/512/1024) are all powers of two, anything else
     * is treated as an unrecognized layout — and the product of the leading
     * axes is the candidate count, which is also the row count of the
     * reshaped view.
     * <p>
     * The plane CONTENT is tied to the INPUT pixel grid, not to the plane
     * grid: plane pixel (i,j) is the segmentation of input pixel (i,j)
     * (measured on device at the 512 input: the raw mask of a square whose
     * input-space bounds are (181,156)..(331,356) came back at exactly
     * (161,133) 192x231 — the top-left quadrant of the 1024² plane — while
     * at the 1024 input the content fills the plane completely, which is why
     * the input==output case never exposed this). The chosen candidate is
     * therefore cropped to the top-left
     * min(plane, MODEL_INPUT_SIZE) content region so the returned mask is
     * the input-space mask the engine's upsample-to-bitmap expects.
     * <p>
     * An empty candidate (no positive logit) is skipped in favor of the
     * next-highest-IOU candidate; when every candidate is empty, an all-zero
     * mask is returned and the engine's hasMask gate rejects it (no mask is
     * ever fabricated).
     */
    private Mat extractBestMask(Mat outputMasks, Mat iouPredictions, String logPrefix) {
        int dims = outputMasks.dims();
        // well-formed fallback dims for the unrecognized-layout path (the
        // engine's hasMask gate rejects the empty mask whatever its size)
        int planeHeight = MODEL_INPUT_SIZE;
        int planeWidth = MODEL_INPUT_SIZE;
        int planeSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE;
        // locate the spatial plane as the trailing square plane of the blob
        // (see the locator rule in the javadoc), the leading axes are the
        // candidate axis/axes
        int candidates = -1;
        long product = 1;
        for (int j = dims - 1; j >= 0; j--) {
            int axisSize = outputMasks.size(j);
            if (axisSize <= 0) {
                break;
            }
            product *= axisSize;
            int side = (int) Math.round(Math.sqrt((double) product));
            if (side >= MIN_PLANE_SIDE && side <= MAX_PLANE_SIDE
                    && isPowerOfTwo(side) && (long) side * side == product) {
                int lastAxis = outputMasks.size(outputMasks.dims() - 1);
                if (lastAxis == side) {
                    // the last axis is the plane width (H,W trailing axes,
                    // H = product / W; equals side for the square planes of
                    // this model family)
                    planeWidth = side;
                    planeHeight = (int) (product / planeWidth);
                } else if (lastAxis == product) {
                    // one flattened trailing axis of side*side elements
                    planeWidth = side;
                    planeHeight = side;
                } else {
                    // a non-square trailing pair (e.g. 512x2048): not a
                    // layout of this model family
                    break;
                }
                candidates = 1;
                for (int i = 0; i < j; i++) {
                    candidates *= outputMasks.size(i);
                }
                break;
            }
            if (product > (long) MAX_PLANE_SIDE * MAX_PLANE_SIDE) {
                break;
            }
        }
        if (candidates > 0) {
            planeSize = planeWidth * planeHeight;
        }
        // the plane content region in input-pixel coordinates (see the
        // javadoc: plane pixel (i,j) is the segmentation of input pixel
        // (i,j)); the returned mask is cropped to it
        int contentWidth = Math.min(planeWidth, MODEL_INPUT_SIZE);
        int contentHeight = Math.min(planeHeight, MODEL_INPUT_SIZE);
        spikeLog(logPrefix + "extraction plane=" + planeWidth + "x" + planeHeight
                + " candidates=" + candidates);
        if (candidates <= 0) {
            // layout not recognized: return a well-formed empty mask, the
            // engine's hasMask gate rejects it
            mLogger.e(mTag, "output_masks layout not recognized, returning an empty mask");
            return new Mat(contentHeight, contentWidth, CvType.CV_8U, new Scalar(0));
        }
        // 2-D K x planeSize view of the continuous blob, one row read per
        // candidate (n-dimensional bulk get is broken, see the javadoc)
        Mat candidateRows = outputMasks.reshape(1, candidates);

        // the IOU blob is read through the reshaped 1xK 2-D view: the
        // reference layout (1,1,K) keeps the candidate values in the first K
        // elements in every plausible layout (leading singleton axes only)
        Mat iouFlat = iouPredictions.reshape(1, 1);
        long iouTotal = iouFlat.total();
        float[] iouBuffer = new float[(int) iouTotal];
        iouFlat.get(0, 0, iouBuffer);
        float[] ious = new float[candidates];
        int iouCount = (int) Math.min((long) candidates, iouTotal);
        for (int k = 0; k < iouCount; k++) {
            ious[k] = iouBuffer[k];
        }
        StringBuilder iouText = new StringBuilder();
        for (int k = 0; k < candidates; k++) {
            if (k > 0) {
                iouText.append(' ');
            }
            iouText.append(ious[k]);
        }
        if (iouCount < candidates) {
            mLogger.e(mTag, "iou_predictions holds " + iouTotal + " values for "
                    + candidates + " candidates, the missing values default to 0");
        }

        // count the positive logits per candidate (one 2-D row read per
        // candidate), an empty best candidate falls back to the
        // next-highest-IOU candidate
        int[] positiveCounts = new int[candidates];
        StringBuilder countText = new StringBuilder();
        float[] candidateLogits = new float[planeSize];
        for (int k = 0; k < candidates; k++) {
            candidateRows.get(k, 0, candidateLogits);
            int count = 0;
            for (int i = 0; i < planeSize; i++) {
                if (candidateLogits[i] >= MASK_LOGIT_THRESHOLD) {
                    count++;
                }
            }
            positiveCounts[k] = count;
            if (k > 0) {
                countText.append(' ');
            }
            countText.append('c').append(k).append('=').append(count);
        }
        spikeLog(logPrefix + "iou values: [" + iouText + "] positive counts: "
                + countText);

        Integer[] candidateOrder = new Integer[candidates];
        for (int k = 0; k < candidates; k++) {
            candidateOrder[k] = k;
        }
        // highest IOU first, the stable sort keeps ascending indexes on ties
        Arrays.sort(candidateOrder, (a, b) -> Float.compare(ious[b], ious[a]));
        for (int rank = 0; rank < candidates; rank++) {
            int candidate = candidateOrder[rank];
            if (positiveCounts[candidate] <= 0) {
                continue;
            }
            // threshold the chosen candidate's logits: logit >= 0 -> mask pixel
            candidateRows.get(candidate, 0, candidateLogits);
            byte[] maskBytes = new byte[planeSize];
            float minLogit = Float.MAX_VALUE;
            float maxLogit = -Float.MAX_VALUE;
            for (int i = 0; i < planeSize; i++) {
                float logit = candidateLogits[i];
                if (logit >= MASK_LOGIT_THRESHOLD) {
                    maskBytes[i] = (byte) MASK_VALUE;
                }
                if (logit < minLogit) {
                    minLogit = logit;
                }
                if (logit > maxLogit) {
                    maxLogit = logit;
                }
            }
            Mat planeMask = new Mat(planeHeight, planeWidth, CvType.CV_8U);
            try {
                planeMask.put(0, 0, maskBytes);
            } catch (RuntimeException | OutOfMemoryError e) {
                planeMask.release();
                throw e;
            }
            spikeLog(logPrefix + "selected candidate " + candidate + " (iou "
                    + ious[candidate] + ", positives " + positiveCounts[candidate]
                    + ", logit min " + minLogit + " max " + maxLogit + ")");
            if (contentWidth < planeWidth || contentHeight < planeHeight) {
                // crop the input-grid content out of the larger fixed output
                // plane (a submat shares the parent data, the clone detaches
                // so the big plane buffer can be freed)
                Mat cropped = planeMask.submat(0, contentHeight, 0, contentWidth)
                        .clone();
                planeMask.release();
                return cropped;
            }
            return planeMask;
        }
        mLogger.e(mTag, "all " + candidates
                + " candidates are empty, returning an empty mask");
        return new Mat(contentHeight, contentWidth, CvType.CV_8U, new Scalar(0));
    }

    /**
     * One-line description of an n-dimensional blob: dims and every axis size,
     * used to log the actual output layout of the model (see extractBestMask).
     */
    private static String sizesText(Mat mat) {
        StringBuilder text = new StringBuilder("dims=").append(mat.dims())
                .append(" sizes=[");
        for (int i = 0; i < mat.dims(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(mat.size(i));
        }
        return text.append(']').toString();
    }

    /**
     * Whether the given positive value is a power of two (the plane-side rule
     * of the mask blob locator, see {@link #extractBestMask}: the resolutions
     * of this model family — 256/512/1024 — are all powers of two)
     */
    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    /**
     * Spike diagnostics printer, dead code unless {@link #SPIKE_LOG} is
     * flipped to true for a spike run: the mLogger debug level is not a
     * release gate (the file logger still writes debug messages in release
     * builds, see BaseProviderModule), so these clearly marked System.out
     * lines stay behind a compile-time constant. They exist because the
     * mLogger output does not reach logcat from the instrumented test
     * process, the connectedCheck spike run iterates from them.
     */
    private static void spikeLog(String message) {
        if (SPIKE_LOG) {
            System.out.println("SPIKE: " + message);
        }
    }

    /**
     * Spike diagnostics of the exact prompt tensors handed to the model (see
     * spikeLog), one entry per prompt slot: pN=(x,y)l<label>.
     */
    private static String promptsText(float[][] promptPoints, float[] promptLabels) {
        StringBuilder text = new StringBuilder("prompts:");
        int count = Math.min(Math.min(promptPoints.length, promptLabels.length),
                MAX_PROMPT_POINTS);
        for (int i = 0; i < count; i++) {
            text.append(" p").append(i).append("=(")
                    .append(promptPoints[i][0]).append(',')
                    .append(promptPoints[i][1]).append(")l").append(promptLabels[i]);
        }
        return text.toString();
    }
}
