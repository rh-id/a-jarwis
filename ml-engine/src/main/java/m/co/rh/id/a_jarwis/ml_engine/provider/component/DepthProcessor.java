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
 * Processor of the Depth Anything V2 Small monocular depth estimation model
 * (upstream https://github.com/DepthAnything/Depth-Anything-V2, ONNX export
 * by the onnx-community Hugging Face org, Apache-2.0 — the Small checkpoint
 * only, the Base/Large checkpoints are CC-BY-NC-4.0 and forbidden, see the
 * {@link MLEngineInstance.ModelType#DEPTH_ESTIMATION} javadoc). Produces the
 * normalized closeness map of the input bitmap: a CV_32F single channel Mat
 * matching the input dimensions with pixel value in 0..1, 0 = farthest,
 * 1 = closest, ready as the blur weight source of
 * {@link DepthEngine#lensBlurBackground}.
 * <p>
 * The model emits a raw disparity-like map (higher value = closer, verified
 * visually on the issue-#6 spike photos), so after the defensive per-image
 * min-max normalization the raw value IS the closeness — an inversion here
 * would flip the bokeh direction (blurring the near plane instead of the
 * far background). The normalization is per-image because the raw scale is
 * arbitrary (relative depth only), and defensive because a numerically flat
 * map (max - min &lt; {@link #FLAT_DEPTH_EPSILON}) cannot be normalized: it
 * is treated as fully-closeness, which the engine's weight math turns into
 * an all-sharp no-op blur.
 * <p>
 * Preprocessing (issue-#6 spike, FIXED conclusions): aspect-PRESERVING
 * lower-bound resize — scale = max(518/w, 518/h), both dims rounded UP to
 * the next multiple of {@link #TILE_MULTIPLE} (518 = 14 * 37, the ViT patch
 * grid of the export, so both dims end at or above the 518 model input) with
 * a bicubic resample — then /255 and the per-channel ImageNet
 * (x - mean) / std normalization. The normalization is done manually on a
 * CV_32FC3 Mat because blobFromImage only supports a SCALAR mean (the
 * AiImageDetectProcessor precedent): the pre-normalized Mat is wrapped into
 * the blob with scalefactor 1.0, scalar mean 0 and swapRB false (the Mat is
 * already RGB-ordered). The output blob of this export is 3-D (1, H, W), so
 * the depth image is extracted through the
 * {@code reshape(1, size(dims - 2))} + clone form: the OpenCV 5 Java
 * bindings silently return zeros for n-dimensional bulk reads (the
 * EfficientSamProcessor spike trap) and {@code Dnn.imagesFromBlob} asserts
 * blob_.dims == 4, both of which would break here — never use them on this
 * blob.
 * <p>
 * A processor instance must never have its {@link #computeDepth(Bitmap)}
 * invoked concurrently, callers are expected to serialize their calls on one
 * instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache recreates a fresh
 * processor on demand.
 */
class DepthProcessor {
    private static final String TAG = "DepthProcessor";
    /**
     * The model input resolution lower bound of the lower-bound convention:
     * after the aspect-preserving resize BOTH dimensions end at or above
     * this (the smaller one exactly, the larger one rounded up), the model
     * accepts any patch-grid-aligned size at or above it
     */
    private static final int MODEL_INPUT_SIZE = 518;
    /**
     * The ViT patch grid of the export: the lower-bound resize rounds both
     * dims up to the next multiple of this (518 = 14 * 37), a non-aligned
     * size fails inside the net
     */
    private static final int TILE_MULTIPLE = 14;
    /**
     * ImageNet normalization of the model input (RGB channel order), applied
     * per channel after the 0..1 scaling: (x/255 - mean) / std. Done manually
     * because blobFromImage only supports a SCALAR mean (same reason as the
     * CLIP normalization in AiImageDetectProcessor)
     */
    private static final double[] IMAGE_NET_MEAN = {0.485, 0.456, 0.406};
    private static final double[] IMAGE_NET_STD = {0.229, 0.224, 0.225};
    /**
     * Flat-map guard threshold of the defensive per-image min-max
     * normalization: a map whose value span is below this cannot be scaled
     * (division by ~0) and carries no usable depth signal, it is treated as
     * fully-closeness instead (no blur, see the class javadoc)
     */
    private static final double FLAT_DEPTH_EPSILON = 1e-6;
    /**
     * The closeness value written on a flat map: fully-close everywhere, so
     * the engine's partition-of-unity weights put all mass on the sharpest
     * (near) blur layer and the result stays visually unchanged
     */
    private static final Scalar FLAT_MAP_CLOSENESS = new Scalar(1.0);
    /**
     * Clamp bounds of the documented 0..1 closeness contract: the final
     * bicubic upsample to the input bitmap dimensions can overshoot the
     * normalized range slightly at strong gradients (cubic ringing, measured
     * by the first instrumented run at ~0.003 beyond the bounds on the
     * landscape photo), the map is forced back into the range after the
     * upsample (see {@link #clampCloseness})
     */
    private static final Scalar CLOSENESS_MIN = new Scalar(0.0);
    private static final Scalar CLOSENESS_MAX = new Scalar(1.0);

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public DepthProcessor(String modelPath, ILogger logger) {
        mModelNet = Dnn.readNetFromONNX(modelPath);
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
    }

    /**
     * Release the native network of this processor: the parsed model weights
     * plus the dnn workspace OpenCV retains inside the net after the
     * forward() calls (~480-550 MB native for this ~99 MB fp32 net, measured
     * by the issue-#6 spike — the release-on-dispose + nudge pattern is
     * mandatory here, the AI-detector precedent). The OpenCV 5.0.0.1 Java
     * bindings expose no public native delete for cv::dnn::Net
     * ({@code Net.finalizeNet()} only prepares the inference session, it
     * frees nothing), the native peer is deleted by the net wrapper's
     * finalizer once this processor drops its reference —
     * {@link MLEngineInstance} nudges the runtime right after releasing so
     * the reclamation happens at that predictable point instead of an
     * arbitrary later GC.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache recreates a fresh processor
     * on demand (the model file is re-parsed, a seconds-long operation).
     * Releasing more than once is a safe no-op. Synchronized on the same
     * monitor as {@link #computeDepth(Bitmap)}, so a release can never run
     * concurrently with an inference (that would be a native use-after-free
     * when a provider dispose races an in-flight task).
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
     * Compute the normalized closeness map of the given bitmap: 0 = farthest,
     * 1 = closest (the raw model output is disparity-like, higher = closer,
     * so the min-max normalized value is the closeness directly — no
     * inversion).
     *
     * @param input bitmap to be estimated, never recycled by this method
     * @return the closeness map Mat (CV_32F single channel, values in 0..1,
     * clamped back into the range after the bicubic upsample, see
     * {@link #clampCloseness}) matching the input bitmap dimensions, the
     * caller owns it and must release() it
     */
    public synchronized Mat computeDepth(Bitmap input) {
        checkNotReleased();
        int width = input.getWidth();
        int height = input.getHeight();
        Mat rgba = null;
        Mat rgb = null;
        Mat crop32 = null;
        Mat normalized = null;
        Mat blob = null;
        Mat output = null;
        Mat depth = null;
        try {
            // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5 behavior)
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            rgba.release();
            rgba = null;
            // aspect-preserving lower-bound resize (see the class javadoc):
            // scale so BOTH dims end at or above the model input size, then
            // round both up to the ViT patch grid multiple
            double scale = Math.max(
                    (double) MODEL_INPUT_SIZE / width,
                    (double) MODEL_INPUT_SIZE / height);
            int targetWidth = roundUpToTileMultiple(
                    (int) Math.round(width * scale));
            int targetHeight = roundUpToTileMultiple(
                    (int) Math.round(height * scale));
            Imgproc.resize(rgb, rgb, new Size(targetWidth, targetHeight),
                    0, 0, Imgproc.INTER_CUBIC);
            // per-channel ImageNet normalization, mirroring the manual
            // normalization shape of AiImageDetectProcessor: first the 0..1
            // scaling, then the split channels are converted with
            // out = in * (1/std) - mean/std (one convertTo per channel),
            // then merged back
            crop32 = new Mat();
            rgb.convertTo(crop32, CvType.CV_32F, 1.0 / 255.0, 0.0);
            rgb.release();
            rgb = null;
            normalized = new Mat();
            List<Mat> channels = new ArrayList<>();
            try {
                Core.split(crop32, channels);
                for (int c = 0; c < channels.size(); c++) {
                    channels.get(c).convertTo(channels.get(c), CvType.CV_32F,
                            1.0 / IMAGE_NET_STD[c],
                            -IMAGE_NET_MEAN[c] / IMAGE_NET_STD[c]);
                }
                Core.merge(channels, normalized);
            } finally {
                for (Mat channel : channels) {
                    channel.release();
                }
            }
            crop32.release();
            crop32 = null;
            // the Mat is already RGB-ordered (COLOR_RGBA2RGB above) and
            // fully normalized, so swapRB stays false and blobFromImage only
            // wraps it into the NCHW blob at the model input size
            // (scalefactor 1, scalar mean 0 — the normalization is pre-done)
            blob = Dnn.blobFromImage(normalized, 1.0,
                    new Size(targetWidth, targetHeight),
                    new Scalar(0, 0, 0), false, false);
            normalized.release();
            normalized = null;
            mModelNet.setInput(blob);
            output = mModelNet.forward();
            blob.release();
            blob = null;
            // the output blob is 3-D (1, H, W): reshape to a 2-D H x W view
            // and clone it — reshape shares the continuous blob memory
            // without a copy, the clone detaches so the blob can be released.
            // NEVER imagesFromBlob (asserts blob_.dims == 4) and NEVER a bulk
            // n-dim get (silently reads zeros) on this blob, both are the
            // documented OpenCV 5 Java bindings traps
            int mapRows = (int) output.size(output.dims() - 2);
            depth = output.reshape(1, mapRows).clone();
            output.release();
            output = null;
            normalizeCloseness(depth);
            // upsample the map back to the input bitmap resolution, bicubic
            // like the input resample (the weights are smooth gradients)
            Imgproc.resize(depth, depth, new Size(width, height),
                    0, 0, Imgproc.INTER_CUBIC);
            clampCloseness(depth);
            Mat result = depth;
            // the ownership moved to the caller, the finally below must not
            // release the returned Mat (it only cleans up the failure paths
            // where a Mat was still locally owned when an exception crossed)
            depth = null;
            return result;
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
            if (crop32 != null) {
                crop32.release();
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
            if (depth != null) {
                depth.release();
            }
        }
    }

    /**
     * Defensive per-image min-max normalization of the raw (disparity-like)
     * map to 0..1 closeness, in place. Per-image because the raw scale is
     * arbitrary relative depth. Flat guard: a map whose value span is below
     * {@link #FLAT_DEPTH_EPSILON} cannot be scaled and carries no usable
     * depth signal — it is written as fully-closeness (all 1.0) instead,
     * which keeps the downstream weight math defined and the blur a no-op.
     */
    private void normalizeCloseness(Mat depth) {
        Core.MinMaxLocResult minMax = Core.minMaxLoc(depth);
        double min = minMax.minVal;
        double max = minMax.maxVal;
        if (max - min < FLAT_DEPTH_EPSILON) {
            mLogger.d(mTag, "flat depth map (max-min<1e-6),"
                    + " treating as fully-closeness (no blur)");
            depth.setTo(FLAT_MAP_CLOSENESS);
            return;
        }
        // out = in * 1/(max-min) - min/(max-min), CV_32F -> CV_32F so the
        // conversion runs on a detached Mat instead of in place (an in-place
        // convertTo is only safe when the target type matches, this keeps
        // the guard future-proof)
        Mat normalized = new Mat();
        try {
            depth.convertTo(normalized, CvType.CV_32F,
                    1.0 / (max - min), -min / (max - min));
            normalized.copyTo(depth);
        } finally {
            normalized.release();
        }
    }

    /**
     * Force the upsampled map back into the documented 0..1 closeness range,
     * in place: the bicubic upsample to the input bitmap dimensions can
     * overshoot the normalized range slightly at strong gradients (cubic
     * ringing, measured by the first instrumented run at ~0.003 beyond the
     * bounds on the landscape photo) even though the pre-resize normalized
     * map is exactly in range. Left unclamped the overshoot breaks the
     * 0..1 contract of {@link #computeDepth} and leaks past-1/under-0
     * closeness into the engine's weight math (past the partition-of-unity
     * assumptions of its smoothstep ramps). Vectorized in-place
     * Core.min/Core.max — the element-wise ops keep the size and type, so
     * the in-place form is safe.
     */
    private static void clampCloseness(Mat depth) {
        Core.min(depth, CLOSENESS_MAX, depth);
        Core.max(depth, CLOSENESS_MIN, depth);
    }

    /**
     * Round up to the next multiple of {@link #TILE_MULTIPLE}, so both dims
     * of the lower-bound convention end on the ViT patch grid (and at or
     * above the 518 model input, since the lower-bound scale already pushes
     * the smaller dim to exactly 518 = 37 * 14)
     */
    private static int roundUpToTileMultiple(int value) {
        return ((value + TILE_MULTIPLE - 1) / TILE_MULTIPLE) * TILE_MULTIPLE;
    }
}
