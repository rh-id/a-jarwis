package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;

/**
 * Processor of the IS-Net general-use salient-object segmentation model
 * (upstream https://github.com/xuebinqin/DIS, Apache-2.0; the ONNX export is
 * the rembg distribution https://github.com/danielgatis/rembg, MIT, see the
 * {@link MLEngineInstance.ModelType#SUBJECT_SEG} javadoc for the licence
 * chain and the issue-#4 spike record). Produces a soft subject alpha mask
 * (CV_8U, 0..255) at the resolution of the input bitmap: 0 = background,
 * 255 = subject, softened towards the middle at the segmentation edge so the
 * mask can be composited without a hard cut.
 * <p>
 * Preprocessing (rembg ISNetSession convention, verified by the issue-#4
 * SPIKE #2 device runs): aspect-DISTORTING resize to the fixed 1024x1024
 * model input (no letterbox), RGBA2RGB, then (x/255 - 0.5) with NO std
 * division. The normalization is done manually because blobFromImage only
 * supports a SCALAR mean subtracted before the scale factor (the
 * AiImageDetectProcessor manual pattern); the rembg mean/std are
 * channel-UNIFORM ((0.5, 0.5, 0.5) / (1.0, 1.0, 1.0)) and are applied as one
 * convertTo per split channel so the shape stays ready for per-channel
 * values.
 * <p>
 * Output: a single-channel CV_32F salience map at the model resolution
 * (blob shape (1, 1, 1024, 1024), verified on device), read through
 * {@code Dnn.imagesFromBlob} (asserts dims==4 — the documented OpenCV 5
 * Java bindings traps forbid a bulk n-dim get, it silently reads zeros).
 * The raw map is a salience score spanning 0..1, NOT a logit: the
 * postprocess is the DIS inference.py min-max normalization to 0..255, NOT
 * a sigmoid (a sigmoid on the raw map would flatten the whole mask into the
 * 0.27..0.73 ghost range). The normalization is defended by TWO gates in
 * {@code normalizeSalience}: the flat guard AND the raw-signal contrast
 * gate ({@code max - mean} on the RAW map) — without the contrast gate a
 * shapeless subject-less input (uniform gray, smooth gradients) min-max
 * stretches into a full-contrast ghost mask and passes the engine's area
 * check. The pipeline tail is the subject mode of the shared
 * {@link SegMaskPostprocess}, in the fixed order: normalize (the gates)
 * → {@code hardenInterior} (re-solidify the translucent interior of the
 * ISNet output) → feather + upsample.
 * <p>
 * A processor instance must never have its {@link #computeMask(Bitmap)}
 * invoked concurrently, callers are expected to serialize their calls on
 * one instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache recreates a
 * fresh processor on demand (through the native-heap settle of
 * {@link MLEngineInstance#getSubjectSegProcessor()}, see the session-lifetime
 * memory law in the ModelType record).
 */
class SubjectSegProcessor {
    private static final String TAG = "SubjectSegProcessor";
    /**
     * The fixed model input resolution, the model is trained on an aspect
     * distorting resize at this size (no letterbox padding), so the aspect
     * distortion here matches the training preprocessing and no padding is
     * added
     */
    private static final int MODEL_INPUT_SIZE = 1024;
    /**
     * rembg ISNetSession normalization of the model input: per channel
     * (x/255 - 0.5), NO std division (mean (0.5, 0.5, 0.5), std (1.0, 1.0,
     * 1.0) — different from the ImageNet-style normalization of the
     * BiRefNet/Depth Anything candidates)
     */
    private static final double NORMALIZE_SCALE_FACTOR = 1.0 / 255.0;
    private static final double NORMALIZE_BETA = -0.5;
    /**
     * A mask (fully opaque pixel sum) covering less than this fraction of
     * the image area is treated as "no subject detected", it absorbs false
     * positives of the model that segment only a few scattered regions
     * (the same rationale and value as PersonSegEngine's MIN_PERSON_AREA_RATIO)
     */
    static final double MIN_SUBJECT_AREA_RATIO = 0.002;
    /**
     * Flat-map guard threshold of the defensive per-image min-max
     * normalization: a map whose value span is below this cannot be scaled
     * (division by ~0) and carries no usable salience signal, it is written
     * as an all-zero mask instead (no subject, the DepthProcessor
     * flat-guard precedent)
     */
    private static final double FLAT_SALIENCE_EPSILON = 1e-6;
    /**
     * Raw-signal contrast gate threshold of
     * {@code normalizeSalience}: a raw salience map whose
     * {@code max - mean} is below this carries no PEAK — a real subject
     * produces a strong raw salience peak above the image's average
     * salience, while a shapeless subject-less input (uniform gray, a
     * smooth gradient) stays near-flat on the raw grid and its min-max
     * normalization would inflate that shapeless noise into a full-contrast
     * mask that passes the {@link #MIN_SUBJECT_AREA_RATIO} area gate
     * (measured against the bundled fixtures: uniform gray ratio 0.0056,
     * linear gradients 0.0022-0.0049 — all of them previously PASSED).
     * Such a map is written as an all-zero mask (no subject), the SAME
     * path as the flat guard. CALIBRATED value against the pinned model
     * (sha256 60920e99..., measured offline with onnxruntime 1.29.0 by
     * replicating the on-device preprocessing exactly: RGB order,
     * INTER_LINEAR distort resize to the 1024 input, x/255 - 0.5): the
     * lowest real-subject contrast is 0.583 (the dog fixture), the highest
     * subject-less contrast is 0.044 (uniform gray; linear gradient 0.043,
     * grass texture 0.012), and 0.1 sits DELIBERATELY below the geometric
     * midpoint (~0.16) to bias against over-rejection of real subjects.
     * The full-frame-subject over-rejection probe measured 0.685 (the
     * bundled close-up fixture), so the mean≈max over-rejection class has
     * ample margin under this gate (see SubjectSegProcessorTest).
     */
    static final double RAW_SALIENCE_CONTRAST_MIN = 0.1;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public SubjectSegProcessor(String modelPath, ILogger logger) {
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
        Net net;
        try {
            net = Dnn.readNetFromONNX(modelPath);
            if (net.empty()) {
                // readNetFromONNX does not reliably throw on an unparseable
                // graph, an empty net is its silent failure form
                net = null;
            }
        } catch (Exception e) {
            // the load failure must surface as the IllegalStateException
            // contract, not as a raw OpenCV exception escaping the
            // constructor (the AiImageDetectProcessor form)
            mLogger.e(TAG, "Failed to load " + mTag
                    + " with the default DNN engine", e);
            net = null;
        }
        if (net == null) {
            throw new IllegalStateException("Failed to load " + mTag
                    + " with the default DNN engine");
        }
        mModelNet = net;
    }

    /**
     * Release the native network of this processor: the parsed model weights
     * plus the dnn workspace OpenCV retains inside the net after the
     * forward() calls (~1 GB native retained after the forwards, measured
     * by the issue-#4 SPIKE #2 — this is why the processor cache is
     * session-lifetime and the release-on-dispose + nudge pattern is
     * mandatory). The OpenCV 5 Java bindings expose no public native delete
     * for cv::dnn::Net ({@code Net.finalizeNet()} only prepares the
     * inference session, it frees nothing), the native peer is deleted by
     * the net wrapper's finalizer once this processor drops its reference —
     * {@link MLEngineInstance} nudges the runtime right after releasing so
     * the reclamation happens at that predictable point instead of an
     * arbitrary later GC.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache recreates a fresh processor
     * on demand (the model file is re-parsed behind the native-heap settle,
     * a seconds-long operation). Releasing more than once is a safe no-op.
     * Synchronized on the same monitor as {@link #computeMask(Bitmap)}, so
     * a release can never run concurrently with an inference (that would be
     * a native use-after-free when a provider dispose races an in-flight
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
     * Compute the soft alpha mask of the salient subject in the given
     * bitmap.
     *
     * @param input bitmap to be segmented, never recycled by this method
     * @return the native mask Mat (CV_8U single channel) matching the input
     * dimensions, the caller owns it and must release() it
     */
    public synchronized Mat computeMask(Bitmap input) {
        checkNotReleased();
        int width = input.getWidth();
        int height = input.getHeight();
        Mat rgba = null;
        Mat rgb = null;
        Mat scaled = null;
        Mat normalized = null;
        Mat blob = null;
        Mat output = null;
        Mat mask = null;
        try {
            // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5 behavior)
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            rgba.release();
            rgba = null;
            // aspect distorting resize to the model input size
            // (see MODEL_INPUT_SIZE)
            Imgproc.resize(rgb, rgb,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    0, 0, Imgproc.INTER_LINEAR);
            // manual rembg normalization (x/255 - 0.5 per channel, NO std
            // division): first the 0..1 scaling, then each split channel is
            // converted with the uniform mean offset (one convertTo per
            // channel, the AiImageDetectProcessor pattern — blobFromImage
            // cannot express this per-channel beta), then merged back
            scaled = new Mat();
            rgb.convertTo(scaled, CvType.CV_32F, NORMALIZE_SCALE_FACTOR, 0.0);
            rgb.release();
            rgb = null;
            normalized = new Mat();
            List<Mat> channels = new ArrayList<>();
            try {
                Core.split(scaled, channels);
                for (int c = 0; c < channels.size(); c++) {
                    channels.get(c).convertTo(channels.get(c), CvType.CV_32F,
                            1.0, NORMALIZE_BETA);
                }
                Core.merge(channels, normalized);
            } finally {
                for (Mat channel : channels) {
                    channel.release();
                }
            }
            scaled.release();
            scaled = null;
            // the Mat is already RGB-ordered (COLOR_RGBA2RGB above) and
            // fully normalized, so swapRB stays false and blobFromImage
            // only wraps it into the NCHW blob at the model input size
            // (scalefactor 1, scalar mean 0 — the normalization is pre-done)
            blob = Dnn.blobFromImage(normalized, 1.0,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    new Scalar(0, 0, 0), false, false);
            normalized.release();
            normalized = null;
            mModelNet.setInput(blob);
            output = mModelNet.forward();
            blob.release();
            blob = null;
            mask = extractSalienceMask(output);
            output.release();
            output = null;
            // subject-mode interior hardening FIRST (the normalized mask is
            // still at the model resolution here): re-solidify the
            // translucent interior the ISNet output shows on fabric-textured
            // subjects, alpha only rises (see SegMaskPostprocess)
            SegMaskPostprocess.hardenInterior(mask, MODEL_INPUT_SIZE);
            // then the shared segmentation tail: feather at the model
            // resolution, then upsample to the input bitmap resolution
            // (see SegMaskPostprocess)
            SegMaskPostprocess.featherAndUpsample(mask, MODEL_INPUT_SIZE,
                    width, height);
            Mat result = mask;
            // the ownership moved to the caller, the finally below must not
            // release the returned Mat (it only cleans up the failure paths
            // where a Mat was still locally owned when an exception crossed)
            mask = null;
            return result;
        } finally {
            // every Mat is released on every path: an inference failing with
            // a native "Insufficient memory" CvException must not leak the
            // (multi-megabyte) image blob
            if (rgba != null) {
                rgba.release();
            }
            if (rgb != null) {
                rgb.release();
            }
            if (scaled != null) {
                scaled.release();
            }
            if (normalized != null) {
                normalized.release();
            }
            if (blob != null) {
                blob.release();
            }
            if (output != null) {
                output.release();
            }
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * Extract the raw CV_32F salience map out of the output blob and
     * normalize it in place to a CV_8U 0..255 mask. The blob is the
     * verified (1, 1, 1024, 1024) shape: {@code Dnn.imagesFromBlob} (asserts
     * dims==4) hands out one Mat per image as views into the blob memory,
     * so the map is cloned out before the blob view list is released (NEVER
     * a bulk n-dim get — it silently reads zeros in the OpenCV 5 Java
     * bindings, the EfficientSamProcessor/DepthProcessor spike trap). A
     * multi-channel image (a graph change) is not fatal: the FIRST channel
     * (the main map, the DIS convention) is used and the deviation is
     * logged.
     */
    private Mat extractSalienceMask(Mat output) {
        List<Mat> images = new ArrayList<>();
        Dnn.imagesFromBlob(output, images);
        try {
            Mat image = images.get(0);
            Mat map;
            if (image.channels() == 1) {
                // clone detaches the view from the blob memory the image
                // list still shares
                map = image.clone();
            } else {
                // the verified export is single-channel; more channels mean
                // a graph change — use the first (main) map and tell
                mLogger.e(TAG, "Unexpected " + image.channels()
                        + "-channel salience output, using channel 0");
                List<Mat> channels = new ArrayList<>();
                try {
                    Core.split(image, channels);
                    map = channels.get(0).clone();
                } finally {
                    for (Mat channel : channels) {
                        channel.release();
                    }
                }
            }
            try {
                normalizeSalience(map);
            } catch (RuntimeException e) {
                // the normalized mask is never handed out on this path,
                // its native memory must not wait for the next GC
                map.release();
                throw e;
            }
            return map;
        } finally {
            for (Mat image : images) {
                image.release();
            }
        }
    }

    /**
     * Defensive per-image min-max normalization of the raw salience map to
     * a CV_8U 0..255 mask, in place (the DIS inference.py postprocess, NOT
     * a sigmoid — the raw output is a salience score, not a logit): the
     * raw minimum lands exactly on 0 and the raw maximum exactly on 255
     * through the saturating convertTo (the LaMaProcessor
     * saturating-convertTo convention, safer than a wrap-cast). Flat guard:
     * a map whose value span is below {@link #FLAT_SALIENCE_EPSILON}
     * carries no usable signal and is written as an all-zero mask
     * (no subject), which the engine's area gate reports as "no subject".
     * <p>
     * Raw-signal contrast gate (alongside the flat guard, on the RAW CV_32F
     * map BEFORE the min-max stretch): a map whose {@code max - mean} is
     * below {@link #RAW_SALIENCE_CONTRAST_MIN} has no salience PEAK — it is
     * written as an all-zero mask (the SAME no-subject path, zero engine
     * and API changes). WHY max-minus-mean: min-max normalization pins
     * whatever tiny variation a shapeless image carries to the FULL
     * 0..255 contrast, so a uniform gray frame or a smooth gradient (the
     * measured area ratios 0.0056 and 0.0022-0.0049) would previously
     * stretch past the {@link #MIN_SUBJECT_AREA_RATIO} area gate and export
     * as a faint ghost; a real subject instead produces a strong raw peak
     * clearly above the image's average salience, which survives the gate.
     * The known over-rejection class is a subject filling the WHOLE frame:
     * the peak shrinks relative to the average and {@code max - mean}
     * approaches zero — the bundled close-up fixture
     * (SubjectSegProcessorTest) guards this class. The raw min/max/mean
     * values are permanently logged at debug for the gate calibration and
     * future diagnostics.
     */
    private void normalizeSalience(Mat map) {
        Core.MinMaxLocResult minMax = Core.minMaxLoc(map);
        double min = minMax.minVal;
        double max = minMax.maxVal;
        // one extra call on the raw CV_32F map, the minMaxLoc above is
        // already computed anyway
        double mean = Core.mean(map).val[0];
        // permanent raw-signal dump: the gate calibration evidence and the
        // future diagnostics baseline (the "flat salience map" log style)
        mLogger.d(mTag, "raw salience min=" + min + " max=" + max
                + " mean=" + mean + " max-mean=" + (max - mean));
        if (max - min < FLAT_SALIENCE_EPSILON) {
            mLogger.d(mTag, "flat salience map (max-min<1e-6),"
                    + " writing an all-zero mask (no subject)");
            map.setTo(Scalar.all(0));
            return;
        }
        if (max - mean < RAW_SALIENCE_CONTRAST_MIN) {
            mLogger.d(mTag, "shapeless salience map (max-mean<"
                    + RAW_SALIENCE_CONTRAST_MIN + "),"
                    + " writing an all-zero mask (no subject)");
            map.setTo(Scalar.all(0));
            return;
        }
        // CV_32F -> CV_8U through the saturating form out = in *
        // 255/(max-min) - min*255/(max-min) so the extremes pin to 0/255;
        // the conversion runs into a detached Mat instead of in place (an
        // in-place convertTo is only safe when the target type matches,
        // the DepthProcessor.normalizeCloseness guard)
        Mat normalized = new Mat();
        try {
            map.convertTo(normalized, CvType.CV_8U,
                    255.0 / (max - min), -min * 255.0 / (max - min));
            normalized.copyTo(map);
        } finally {
            normalized.release();
        }
    }

    /**
     * Subject detection of a raw native mask Mat (same shape as
     * {@link PersonSegEngine} hasPersonImpl): true when the masked area
     * (sum of the opaque pixel values) covers at least
     * {@link #MIN_SUBJECT_AREA_RATIO} of the image area, so the engine can
     * report "no subject detected" instead of compositing an empty edit
     * result.
     *
     * @param mask mask created by {@link #computeMask(Bitmap)}
     * @return true when the mask covers the minimum subject area ratio
     */
    static boolean hasSubject(Mat mask) {
        if (mask.empty()) {
            return false;
        }
        double area = (double) mask.cols() * mask.rows();
        double sum = Core.sumElems(mask).val[0];
        return sum >= area * 255.0 * MIN_SUBJECT_AREA_RATIO;
    }
}
