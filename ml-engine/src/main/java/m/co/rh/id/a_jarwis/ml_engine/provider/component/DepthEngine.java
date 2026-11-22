package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderDisposable;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Depth estimation engine of the Depth Anything V2 Small model, enables the
 * depth-aware whole-image bokeh ("lens blur") effect: the whole image is
 * blurred in graduated layers and the layers are blended per pixel by the
 * closeness map (far = strongest blur, near = sharp), so the bokeh follows
 * the real scene depth. A standalone whole-image effect with no person
 * segmentation involved — any photo (landscape, food, product shot) works.
 * <p>
 * The depth cache is fully engine-internal: no depth handle crosses to :app,
 * the public surface is key-driven ({@link #prepareDepth} warm-up plus the
 * key-driven {@link #lensBlurBackground(Object, Bitmap, int, float)} blend).
 * The asymmetry vs the person segmentation engine is deliberate: a
 * PersonSegMask handle is load-bearing there because three consumer methods
 * (hasPerson/blurBackground/removeBackground) work on it, while the lens
 * blur blend is the only consumer of a depth map here.
 * <p>
 * The processing is a heavy native operation, callers are expected to run it
 * serialized (e.g. on a single background executor).
 */
public class DepthEngine implements ProviderDisposable {
    private static final String TAG = "DepthEngine";
    /**
     * Number of graduated Gaussian blur layers of the whole image, far
     * (index 0, the full-strength kernel) to near (index
     * {@link #BLUR_LAYER_COUNT} - 1, the sharpest kernel). Four layers are
     * the balance between a visible depth banding-free bokeh ramp and the
     * native cost (each layer is a full-frame Gaussian blur of the image);
     * the smoothstep weight ramps below overlap so no hard band boundary is
     * visible even at this coarse layer count
     */
    private static final int BLUR_LAYER_COUNT = 4;
    /**
     * Minimum Gaussian blur kernel size, OpenCV requires a positive odd
     * kernel size
     */
    private static final int BLUR_KERNEL_SIZE_MIN = 3;
    /**
     * Clamp bounds of the closeness/weight domain: the smoothstep ramp
     * argument is clamped to 0..1 before the polynomial is evaluated
     * (clamping the argument instead of the result is load-bearing for the
     * partition of unity, see {@link #computeLayerWeight}) and a defensively
     * resized closeness map is clamped back into 0..1 after its bicubic
     * upsample (cubic ringing can overshoot the normalized range even from
     * an exactly-in-range source, see
     * {@link DepthProcessor#clampCloseness} for the measured numbers)
     */
    private static final Scalar CLAMP_MIN = new Scalar(0.0);
    private static final Scalar CLAMP_MAX = new Scalar(1.0);

    private final ILogger mLogger;
    private final ProviderValue<MLEngineInstance> mMLEngine;
    /**
     * Lazy depth map cache keyed by an opaque cache key (e.g. an image index
     * of an editor batch), a key without a cached entry is estimated on
     * first read, improving the repeated display rebuild of the same image
     * on config toggles (the inference is not re-run). Fully engine-internal,
     * the raw Mat never leaves this package
     */
    private final ConcurrentHashMap<Object, Mat> mDepthCache;

    public DepthEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
        mDepthCache = new ConcurrentHashMap<>();
    }

    /**
     * Check whether the Depth Anything V2 Small depth estimation model is
     * downloaded and usable, so the lens blur flow can prompt the model
     * download before the editor opens.
     *
     * @return true when the depth estimation model file is already downloaded
     */
    public boolean isAvailable() {
        return mMLEngine.get().isModelAvailable(ModelType.DEPTH_ESTIMATION);
    }

    /**
     * Opaque model id of the depth estimation model, used to key download
     * requests through ModelDownloadDialog/ModelDownloadWorker (the engines
     * are the only model id producers).
     *
     * @return the opaque model id of the depth estimation model
     */
    public String depthModelId() {
        return ModelType.DEPTH_ESTIMATION.getId();
    }

    /**
     * Internal resolver of the per-key cached closeness map (same package
     * and tests only, no map handle crosses to :app): the first read of a
     * key without a cached map runs the depth inference, later reads of the
     * same key reuse the cached map until it is released (see releaseDepth),
     * so repeated display rebuilds of the same image on config toggles never
     * re-run the inference. The public contract lives in
     * {@link #prepareDepth} (cache warm-up) and the key-driven
     * {@link #lensBlurBackground(Object, Bitmap, int, float)} (cache read +
     * blend). The map has the same dimensions as the input bitmap, pixel
     * value in 0..1 (0 = farthest, 1 = closest).
     *
     * @param cacheKey opaque key identifying the depth map (e.g. the image
     *                 index in a batch of picked images)
     * @param bitmap   bitmap to be estimated, never recycled by this method
     */
    Mat getDepth(Object cacheKey, Bitmap bitmap) {
        Mat depth = mDepthCache.get(cacheKey);
        if (depth == null) {
            depth = mMLEngine.get().getDepthProcessor()
                    .computeDepth(bitmap);
            // two reads of the same key can infer concurrently (e.g. two
            // images changed close together), putIfAbsent keeps the first
            // map and the loser copy is discarded
            Mat cachedDepth = mDepthCache.putIfAbsent(cacheKey, depth);
            if (cachedDepth == null) {
                mLogger.d(TAG, "Depth map cached for key " + cacheKey);
                return depth;
            }
            depth.release();
            depth = cachedDepth;
        }
        return depth;
    }

    /**
     * Warm the per-key depth cache without returning any handle: runs the
     * depth inference for the given bitmap at the bitmap's resolution and
     * caches the map under the opaque key, so the later
     * {@link #lensBlurBackground(Object, Bitmap, int, float)} call with the
     * SAME key blends from the warmed map instead of re-running the
     * inference. The caller MUST use the same opaque key in both calls (the
     * opaque-key contract mirrors PersonSegEngine.getMask) — a different key
     * would silently estimate the map again. The bitmap is never recycled by
     * this method.
     *
     * @param cacheKey opaque key identifying the depth map (e.g. the image
     *                 index in a batch of picked images)
     * @param bitmap   bitmap to be estimated, never recycled by this method
     */
    public void prepareDepth(Object cacheKey, Bitmap bitmap) {
        getDepth(cacheKey, bitmap);
    }

    /**
     * Release the cached depth map of the given cache key, a later read of
     * that key (e.g. the same image revisited) re-runs the inference. Keys
     * without a cached map are ignored.
     *
     * @param cacheKey key possibly holding a cached depth map
     */
    public void releaseDepth(Object cacheKey) {
        Mat depth = mDepthCache.remove(cacheKey);
        if (depth != null) {
            depth.release();
        }
    }

    /**
     * Release every cached depth map, an editor session must call this when
     * it is disposed so the session maps do not leak into the next session
     * (the same keys belong to different images then)
     */
    public void releaseAllDepths() {
        for (Object cacheKey : mDepthCache.keySet()) {
            releaseDepth(cacheKey);
        }
    }

    /**
     * Release the cached depth processor (frees its native net, see
     * {@link MLEngineInstance#releaseDepthProcessor()}): OpenCV dnn retains
     * a large workspace inside the net after its inference (~480-550 MB
     * native for the ~99 MB fp32 net, measured by the issue-#6 spike), so
     * the lens blur editor releases the processor when its session
     * ends instead of keeping it for the app lifetime (the mandatory
     * release-on-dispose + nudge pattern, the AI-detector precedent). The
     * next {@link #getDepth} lazily recreates the processor from the model
     * file on demand (a seconds-long re-parse).
     */
    public void releaseProcessor() {
        mMLEngine.get().releaseDepthProcessor();
    }

    /**
     * Drain every cached depth map and the cached depth processor when the
     * provider tears down: belt-and-braces — the editor page already
     * releases its session maps and processor on dispose
     * (LensBlurEditorPage), this drains anything left cached when a page
     * forgot, so no native memory outlives the provider.
     *
     * @param context the context of the provider, unused here (draining the
     *                caches needs no context)
     */
    @Override
    public void dispose(Context context) {
        releaseAllDepths();
        releaseProcessor();
    }

    /**
     * Process the bitmap with the depth-aware bokeh ("lens blur"): the whole
     * image is Gaussian-blurred into {@link #BLUR_LAYER_COUNT} graduated
     * layers and the layers are blended per pixel by the closeness map
     * resolved for the given opaque key (far pixels get the strongest blur,
     * near pixels stay sharp). The depth-blended frame IS the result — a
     * standalone whole-image effect, no person segmentation or compositing
     * is involved.
     * <p>
     * The cacheKey is the SAME opaque key the caller passed to
     * {@link #prepareDepth} (the opaque-key contract mirrors
     * PersonSegEngine.getMask): on a cache hit the warmed map is blended
     * without re-running the inference; on a cache miss (e.g. the first
     * display of an image, or the full resolution save/share of a restored
     * session whose engine cache is empty) the map is estimated just-in-time
     * from originalBitmap at the bitmap's resolution — full resolution at
     * save/share, preview resolution at display. A cached map whose
     * dimensions differ from the bitmap dimensions (typically the cached
     * preview-resolution map reused at the full resolution save/share) is
     * bicubic-resized and re-clamped to the 0..1 closeness domain
     * internally. No native handle is exposed to callers.
     * <p>
     * The blur kernel convention mirrors PersonSegEngine.createBlurredBitmap
     * exactly so the strength slider produces the same per-strength look as
     * the portrait blur mode: the full resolution kernel is computed from
     * the bitmap min dimension per strength (see
     * FaceEngine#computeFullKernelSize), scaled down to this bitmap
     * resolution and forced to a positive odd value;
     * layer k (k = 0 far to k = K-1 near) uses
     * max(3, odd(round(maxKernel * (1 - k/(K-1))))).
     * <p>
     * The per-pixel layer weights are a partition of unity of overlapping
     * smoothstep ramps over the closeness c in 0..1:
     * w_k(c) = smoothstep((K-1)*c - k + 1) - smoothstep((K-1)*c - k) with
     * smoothstep(x) = x^2 * (3 - 2x) on x clamped to [0,1]. The ramps
     * overlap and sum to exactly 1 per pixel (telescoping over the clamped
     * smoothstep endpoints) — hard thresholds between the layers would
     * instead produce visible bokeh bands, an acceptance-criterion violation.
     * A flat closeness map (c = 1 everywhere after the processor's flat
     * guard) puts all weight on the sharpest (near) layer, so the result is
     * a no-op blur.
     * <p>
     * The blend runs fully vectorized on CV_32F Mats (bitmapToMat RGBA
     * converted to CV_32F, result = sum of w_k * layer_k via
     * Core.multiply/Core.add, no per-pixel Java loop) and is converted back
     * to an ARGB_8888 bitmap.
     *
     * @param cacheKey       the opaque cache key the map was warmed under
     *                       (see {@link #prepareDepth}), also the key a
     *                       just-in-time map is cached under
     * @param originalBitmap bitmap to be processed, never recycled by this
     *                       method
     * @param strength       blur strength (the BlurConfig range)
     * @param scale          preview to full resolution scale factor,
     *                       1.0 means the bitmap is full resolution
     * @return a copy of the originalBitmap with the depth-aware blur applied
     * (ARGB_8888) for a JPEG export
     */
    public Bitmap lensBlurBackground(Object cacheKey, Bitmap originalBitmap,
                                     int strength, float scale) {
        return lensBlurBackground(originalBitmap,
                getDepth(cacheKey, originalBitmap), strength, scale);
    }

    /**
     * Blend the given closeness map over the bitmap, the engine-internal
     * body of {@link #lensBlurBackground(Object, Bitmap, int, float)} (same
     * package and tests only): package-private so the synthetic-map test can
     * bypass the processor and feed a hand-built closeness Mat directly.
     * See the public overload for the blur and weight conventions.
     *
     * @param originalBitmap bitmap to be processed, never recycled by this
     *                       method
     * @param closeness      closeness map of the same scene (pixel value in
     *                       0..1), this method does not release the map;
     *                       when its dimensions differ from the bitmap
     *                       dimensions it is bicubic-resized to the bitmap
     *                       dimensions first
     * @param strength       blur strength (the BlurConfig range)
     * @param scale          preview to full resolution scale factor,
     *                       1.0 means the bitmap is full resolution
     * @return a copy of the originalBitmap with the depth-aware blur applied
     * (ARGB_8888) for a JPEG export
     */
    Bitmap lensBlurBackground(Bitmap originalBitmap, Mat closeness,
                              int strength, float scale) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        if (scale <= 0f) {
            // a failed dimension decode falls back to a full resolution blur
            scale = 1f;
        }
        Mat resizedDepth = null;
        try {
            if (closeness.cols() != width || closeness.rows() != height) {
                // the cached map was estimated at another resolution (the
                // preview map reused by a full resolution save/share), the
                // blend reads the map pixels aligned with the bitmap pixels
                // and a mismatch would produce a shifted depth silently
                resizedDepth = new Mat();
                Imgproc.resize(closeness, resizedDepth, new Size(width, height),
                        0, 0, Imgproc.INTER_CUBIC);
                // the bicubic upsample can ring slightly outside the
                // normalized range even from an exactly-in-range source (the
                // DepthProcessor clamp), keep the 0..1 closeness contract
                // before the weight math (an overshooting closeness would
                // push the smoothstep partition of unity below 1 and darken
                // the blend at those pixels)
                Core.min(resizedDepth, CLAMP_MAX, resizedDepth);
                Core.max(resizedDepth, CLAMP_MIN, resizedDepth);
                closeness = resizedDepth;
            }
            // the depth-blended frame IS the result, there is no person
            // compositing step
            return buildDepthBlurredBitmap(originalBitmap,
                    closeness, strength, scale);
        } finally {
            if (resizedDepth != null) {
                resizedDepth.release();
            }
        }
    }

    /**
     * Build the depth-blended bokeh bitmap, see {@link #lensBlurBackground}
     * for the blur and weight conventions.
     */
    private Bitmap buildDepthBlurredBitmap(Bitmap originalBitmap, Mat closeness,
                                           int strength, float scale) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        // the kernel convention of PersonSegEngine.createBlurredBitmap:
        // the preview min dimension back-scaled to the full resolution min
        // dimension, the full resolution kernel per strength, scaled down to
        // this bitmap resolution and clamped to a positive odd value
        int minDim = Math.min(width, height);
        int fullMinDim = Math.round(minDim / scale);
        int fullKernelSize = FaceEngine.computeFullKernelSize(fullMinDim, strength);
        int maxKernel = Math.round(fullKernelSize * scale);
        if (maxKernel < BLUR_KERNEL_SIZE_MIN) {
            maxKernel = BLUR_KERNEL_SIZE_MIN;
        }
        if (maxKernel % 2 == 0) {
            maxKernel++;
        }
        Mat base8 = null;
        Mat base32 = null;
        Mat result32 = null;
        try {
            base8 = new Mat();
            Utils.bitmapToMat(originalBitmap, base8);
            base32 = new Mat();
            // RGBA float base, the blur layers and the weighted blend all
            // run in CV_32F so the blend never quantizes per layer
            base8.convertTo(base32, CvType.CV_32F, 1.0, 0.0);
            result32 = new Mat(height, width, CvType.CV_32FC4,
                    new Scalar(0, 0, 0, 0));
            for (int k = 0; k < BLUR_LAYER_COUNT; k++) {
                int kernel = layerKernel(maxKernel, k);
                Mat layer = new Mat();
                Mat weight = new Mat();
                Mat weightRgba = new Mat();
                Mat weightedLayer = new Mat();
                try {
                    Imgproc.GaussianBlur(base32, layer,
                            new Size(kernel, kernel),
                            kernel * FaceEngine.BLUR_SIGMA_SCALE);
                    computeLayerWeight(closeness, k, weight);
                    // the OpenCV element-wise multiply needs matching channel
                    // counts (a 1-channel Mat operand is NOT broadcast over a
                    // 4-channel Mat), so the weight is merged into 4 copies
                    // of itself before the per-pixel layer weighting
                    List<Mat> channels = new ArrayList<>();
                    channels.add(weight);
                    channels.add(weight);
                    channels.add(weight);
                    channels.add(weight);
                    Core.merge(channels, weightRgba);
                    Core.multiply(layer, weightRgba, weightedLayer);
                    Core.add(result32, weightedLayer, result32);
                } finally {
                    layer.release();
                    weight.release();
                    weightRgba.release();
                    weightedLayer.release();
                }
            }
            Bitmap result = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            Mat result8 = new Mat();
            try {
                // the partition-of-unity weights keep the blend within the
                // source value range, the saturating CV_32F -> CV_8U cast
                // only absorbs the rounding noise
                result32.convertTo(result8, CvType.CV_8U);
                Utils.matToBitmap(result8, result);
                return result;
            } finally {
                result8.release();
            }
        } finally {
            if (base8 != null) {
                base8.release();
            }
            if (base32 != null) {
                base32.release();
            }
            if (result32 != null) {
                result32.release();
            }
        }
    }

    /**
     * Gaussian kernel of the blur layer at the given index: index 0 (far)
     * takes the full {@code maxKernel}, the last index (near) takes the
     * sharpest kernel (clamped to {@link #BLUR_KERNEL_SIZE_MIN}), forced odd
     * for OpenCV.
     */
    private static int layerKernel(int maxKernel, int layerIndex) {
        int kernel = Math.round(maxKernel
                * (1.0f - layerIndex / (float) (BLUR_LAYER_COUNT - 1)));
        if (kernel < BLUR_KERNEL_SIZE_MIN) {
            kernel = BLUR_KERNEL_SIZE_MIN;
        }
        if (kernel % 2 == 0) {
            kernel++;
        }
        return kernel;
    }

    /**
     * Weight map of the blur layer at the given index over the closeness
     * map, written into {@code weight} (CV_32F single channel):
     * w_k(c) = smoothstep((K-1)*c - k + 1) - smoothstep((K-1)*c - k).
     * <p>
     * The K ramps are a partition of unity: w_k is the rise of the smoothstep
     * over the unit interval [t-k, t-k+1] with t = (K-1)*c, and summing the
     * telescoping series over all k leaves smoothstep(t + 1) -
     * smoothstep(t - (K-1)) which the [0,1] clamp of the smoothstep argument
     * pins to exactly 1 everywhere in c in [0,1]. All evaluation is
     * vectorized (convertTo/multiply/addWeighted), no per-pixel Java loop.
     */
    private static void computeLayerWeight(Mat closeness, int layerIndex,
                                           Mat weight) {
        double rampScale = BLUR_LAYER_COUNT - 1;
        Mat t1 = new Mat();
        Mat t2 = new Mat();
        Mat s1 = new Mat();
        Mat s2 = new Mat();
        try {
            // t1 = (K-1)*c - k + 1, t2 = (K-1)*c - k
            closeness.convertTo(t1, CvType.CV_32F, rampScale, -layerIndex + 1);
            closeness.convertTo(t2, CvType.CV_32F, rampScale, -layerIndex);
            smoothstep(t1, s1);
            smoothstep(t2, s2);
            Core.subtract(s1, s2, weight);
        } finally {
            t1.release();
            t2.release();
            s1.release();
            s2.release();
        }
    }

    /**
     * Smoothstep of the given Mat in place of {@code dst}:
     * s(x) = x^2 * (3 - 2x) evaluated on x clamped to [0,1]. Clamping the
     * ARGUMENT (not the result) is load-bearing: it pins the ramp tails to
     * exactly 0/1, which the partition-of-unity telescoping of
     * {@link #computeLayerWeight} relies on.
     */
    private static void smoothstep(Mat x, Mat dst) {
        Mat clamped = new Mat();
        Mat x2 = new Mat();
        Mat x3 = new Mat();
        try {
            Core.min(x, CLAMP_MAX, clamped);
            Core.max(clamped, CLAMP_MIN, clamped);
            Core.multiply(clamped, clamped, x2);
            Core.multiply(x2, clamped, x3);
            Core.addWeighted(x2, 3.0, x3, -2.0, 0.0, dst);
        } finally {
            clamped.release();
            x2.release();
            x3.release();
        }
    }
}
