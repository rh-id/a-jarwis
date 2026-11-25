package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderDisposable;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Subject segmentation engine of the IS-Net general-use model, removes the
 * background of an arbitrary photo: the salient subject is segmented once
 * and kept as a soft alpha mask, the background is made fully transparent
 * (transparent PNG result) through the mask.
 * <p>
 * WHY A DEDICATED ENGINE (issue #4): the subject background removal feature
 * pivoted from the BiRefNet-lite candidate (NO-GO — its Swin forward
 * workspace blew past 2 GB on the app's gate RAM tiers, the
 * {@link ModelType#SUBJECT_SEG} javadoc holds the spike record) to the
 * IS-Net general-use export, whose pure-CNN graph fits both tiers at a ~1 GB
 * retained dnn workspace. Because of that ~1 GB workspace the cached
 * processor is SESSION-LIFETIME (not app-lifetime like the portrait
 * PersonSegProcessor): the editor session releases it on dispose through
 * {@link #releaseProcessor()} and the next session re-parses the model
 * behind the engine instance's bounded native-heap settle — never hold this
 * processor next to another heavy net.
 * <p>
 * The mask cache is fully ENGINE-INTERNAL, no mask ever leaves the engine
 * (:app has no OpenCV on its classpath, so no public signature names a
 * native type): the caller only ever holds an opaque cache key and the
 * returned bitmaps (the DepthEngine shape).
 * {@link #prepareSubject(Object, Bitmap)} segments a bitmap, caches its
 * soft mask under that key and reports the subject verdict; the key-driven
 * {@link #removeBackground(Object, Bitmap)} composites a bitmap through
 * the mask cached under the key and segments just in time on a cache
 * miss; the one-shot {@link #removeBackground(Bitmap)} is the uncached
 * full resolution path of the save/share export. A cached mask is never
 * handed out: it is only destroyed, through {@link #releaseMask(Object)},
 * {@link #releaseAllMasks()} or the provider dispose.
 * <p>
 * The processing is a heavy native operation, callers are expected to run it
 * serialized (e.g. on a single background executor).
 */
public class SubjectSegEngine implements ProviderDisposable {
    private static final String TAG = "SubjectSegEngine";
    /**
     * Per-key mask cache of the preview segmentation: the editor segments
     * its image once under its key (prepareSubject) and every later display
     * rebuild of that image composites through the cached mask instead of
     * re-running the inference. Fully engine-internal, the raw Mat never
     * leaves this class
     */
    private final ConcurrentHashMap<Object, Mat> mMaskCache;
    private final ILogger mLogger;
    private final ProviderValue<MLEngineInstance> mMLEngine;

    public SubjectSegEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
        mMaskCache = new ConcurrentHashMap<>();
    }

    /**
     * Check whether the IS-Net general-use subject segmentation model is
     * downloaded and usable, so the subject background removal flow can
     * prompt the model download before the editor opens.
     *
     * @return true when the subject segmentation model file is already
     * downloaded
     */
    public boolean isAvailable() {
        return mMLEngine.get().isModelAvailable(ModelType.SUBJECT_SEG);
    }

    /**
     * Opaque model id of the subject segmentation model, used to key
     * download requests through ModelDownloadDialog/ModelDownloadWorker
     * (the engines are the only model id producers).
     *
     * @return the opaque model id of the subject segmentation model
     */
    public String subjectSegModelId() {
        return ModelType.SUBJECT_SEG.getId();
    }

    /**
     * Segment the salient subject of the given bitmap and cache its soft
     * alpha mask under the given opaque cache key through the per-key
     * cache: the first call for a key without a cached mask runs the
     * segmentation, later calls with the same key reuse the cached mask and
     * never look at the bitmap again (the inference is not re-run), until
     * the mask is released (see {@link #releaseMask(Object)}). This warms
     * the key for the later key-driven
     * {@link #removeBackground(Object, Bitmap)} of the same image — the
     * caller MUST use the same opaque key in both calls, a different key
     * would silently segment the image again. No mask is handed out, the
     * mask stays inside the engine.
     * <p>
     * The returned verdict is always that of the mask that ends up cached
     * under the key: when two calls for the same key segment concurrently
     * the cache keeps the first mask, and the verdict of the losing mask is
     * discarded together with the mask itself — so the verdict never
     * describes a mask the key-driven removeBackground would not use.
     * <p>
     * A no-subject mask is cached all the same (the verdict is the
     * caller's gate, the key-driven removeBackground deliberately runs no
     * gate of its own).
     *
     * @param cacheKey opaque key identifying the mask (the editor's key of
     *                 its single image)
     * @param bitmap   bitmap to be segmented, never recycled by this method
     * @return true when the cached mask covers at least the
     * {@link SubjectSegProcessor#MIN_SUBJECT_AREA_RATIO} of the image area,
     * false when it covers less. The gate is the processor's two-step
     * rejection: the raw-signal contrast gate FIRST zeroes a shapeless
     * salience map (uniform gray, smooth gradients) before the min-max
     * normalization can inflate it into a full-contrast mask, then the
     * area check rejects a flat / near-flat model output. The contrast
     * gate has one known over-rejection class — a subject filling the
     * whole frame pushes the raw mean against its max (see
     * SubjectSegProcessor#normalizeSalience); a false verdict is the
     * signal to skip the export
     */
    public boolean prepareSubject(Object cacheKey, Bitmap bitmap) {
        return SubjectSegProcessor.hasSubject(
                resolveCachedMask(cacheKey, bitmap));
    }

    /**
     * Process the bitmap by making its background fully transparent, the
     * subject region keeps the original pixels with the mask as alpha
     * channel. The mask comes from the per-key cache: on a cache hit the
     * bitmap is composited through the mask cached under the key (the one
     * {@link #prepareSubject(Object, Bitmap)} warmed) without re-running
     * the inference; on a cache miss (e.g. a display rebuild after a
     * process death restore, when the engine cache is empty) the bitmap is
     * segmented just in time and its mask is cached under the key. The mask
     * stays owned by the engine cache, this method does not release it.
     * <p>
     * This method runs no subject gate (it never returns null): whether the
     * image has a subject at all is the
     * {@link #prepareSubject(Object, Bitmap)} contract.
     *
     * @param cacheKey       the opaque cache key the mask was prepared under
     *                       (see {@link #prepareSubject(Object, Bitmap)}),
     *                       also the key a just-in-time mask is cached
     *                       under
     * @param originalBitmap bitmap to be processed, never recycled by this
     *                       method
     * @return a copy of the originalBitmap with only the subject kept
     * (ARGB_8888) for a transparent PNG export, owned by the caller
     * @throws IllegalArgumentException when the mask already cached under
     *         the key has other dimensions than the bitmap: the compositing
     *         reads the mask pixels aligned with the bitmap pixels, a
     *         mismatch would produce a shifted or clipped mask silently
     */
    public Bitmap removeBackground(Object cacheKey, Bitmap originalBitmap) {
        return compositeSubject(originalBitmap,
                resolveCachedMask(cacheKey, originalBitmap));
    }

    /**
     * Process the bitmap by making its background fully transparent through
     * a mask computed from the bitmap itself, without using the cache: the
     * one-shot path of the full resolution save/share export, whose mask
     * (full resolution, hence different from the cached preview mask) is
     * never worth caching. The subject gate runs first, exactly like
     * {@link #prepareSubject(Object, Bitmap)} does for the cached path.
     *
     * @param originalBitmap bitmap to be processed, never recycled by this
     *                       method
     * @return a copy of the originalBitmap with only the subject kept
     * (ARGB_8888) for a transparent PNG export, owned by the caller; or
     * null when the mask covers less than the
     * {@link SubjectSegProcessor#MIN_SUBJECT_AREA_RATIO} of the image area
     * — the caller must skip the export then. The same gate as
     * {@link #prepareSubject(Object, Bitmap)}: the raw-signal contrast gate
     * first zeroes a shapeless salience map (uniform gray, smooth
     * gradients), then the area check rejects a flat / near-flat model
     * output
     */
    public Bitmap removeBackground(Bitmap originalBitmap) {
        // the mask is owned by this method alone and released on every path
        Mat mask = mMLEngine.get().getSubjectSegProcessor()
                .computeMask(originalBitmap);
        try {
            if (!SubjectSegProcessor.hasSubject(mask)) {
                return null;
            }
            return compositeSubject(originalBitmap, mask);
        } finally {
            mask.release();
        }
    }

    /**
     * Internal resolver of the per-key cached mask, the single place a mask
     * enters the cache: the first read of a key without a cached mask runs
     * the segmentation, later reads of the same key return the cached mask
     * until it is released (see {@link #releaseMask(Object)}), so the
     * display rebuilds of the same image never re-run the inference. The
     * mask has the same dimensions as the input bitmap,
     * pixel value = the salience of the pixel as part of the subject
     * (0 background, 255 subject, values in between at the feathered
     * segmentation edge).
     * <p>
     * The returned Mat stays owned by the engine cache: the callers of
     * this method only READ it and must never release it.
     *
     * @param cacheKey opaque key identifying the mask (the editor's key of
     *                 its single image)
     * @param bitmap   bitmap to be segmented on a cache miss, never recycled
     */
    private Mat resolveCachedMask(Object cacheKey, Bitmap bitmap) {
        Mat mask = mMaskCache.get(cacheKey);
        if (mask == null) {
            mask = mMLEngine.get().getSubjectSegProcessor()
                    .computeMask(bitmap);
            // two callers can miss the same key concurrently, putIfAbsent
            // keeps the first mask and the losing copy is released
            Mat cachedMask = mMaskCache.putIfAbsent(cacheKey, mask);
            if (cachedMask == null) {
                mLogger.d(TAG, "Mask cached for key " + cacheKey);
                return mask;
            }
            mask.release();
            mask = cachedMask;
        }
        return mask;
    }

    /**
     * Release the cached mask of the given cache key, a later read of that
     * key (e.g. the same image revisited) re-segments it. Keys without a
     * cached mask are ignored.
     *
     * @param cacheKey key possibly holding a cached mask
     */
    public void releaseMask(Object cacheKey) {
        Mat mask = mMaskCache.remove(cacheKey);
        if (mask != null) {
            mask.release();
        }
    }

    /**
     * Release every cached mask, an editor session must call this when it is
     * disposed so the session masks do not leak into the next session (the
     * same keys belong to different images then)
     */
    public void releaseAllMasks() {
        for (Object cacheKey : mMaskCache.keySet()) {
            releaseMask(cacheKey);
        }
    }

    /**
     * Release the cached subject segmentation processor (frees its native
     * net, see {@link MLEngineInstance#releaseSubjectSegProcessor()}):
     * OpenCV dnn retains a large workspace inside the net after its
     * inference (~1 GB native after GC, measured by the issue-#4 SPIKE #2),
     * so the subject background removal editor releases the processor when
     * its session ends instead of keeping it for the app lifetime. The next
     * {@link #prepareSubject(Object, Bitmap)},
     * {@link #removeBackground(Object, Bitmap)} or
     * {@link #removeBackground(Bitmap)} lazily recreates the processor
     * from the model file behind the bounded native-heap settle (a
     * seconds-long re-parse).
     */
    public void releaseProcessor() {
        mMLEngine.get().releaseSubjectSegProcessor();
    }

    /**
     * Drain every cached mask and the cached processor when the provider
     * tears down: belt-and-braces — the editor page already releases its
     * session masks and processor on dispose, this drains anything left
     * cached when a page forgot, so no native memory outlives the provider
     * (the DepthEngine dispose shape, see the ~1 GB workspace memory law in
     * the {@link ModelType#SUBJECT_SEG} javadoc).
     *
     * @param context the context of the provider, unused here (draining the
     *                caches needs no context)
     */
    @Override
    public void dispose(Context context) {
        releaseAllMasks();
        releaseProcessor();
    }

    /**
     * Composite the originalBitmap through the given mask, the shared body
     * of both removeBackground paths: the subject region keeps the original
     * pixels with the mask as alpha channel, everything else becomes fully
     * transparent. The mask is only READ here and is never released (a
     * cached mask belongs to the engine cache, the one-shot mask to the
     * method that computed it).
     *
     * @param originalBitmap bitmap to be processed, never recycled by this
     *                       method
     * @param mask           mask matching the bitmap dimensions
     * @return a copy of the originalBitmap with only the subject kept
     * (ARGB_8888) for a transparent PNG export, owned by the caller
     */
    private static Bitmap compositeSubject(Bitmap originalBitmap, Mat mask) {
        checkMask(mask, originalBitmap);
        Bitmap maskBitmap = toMaskBitmap(mask);
        Bitmap result = null;
        boolean delivered = false;
        try {
            result = Bitmap.createBitmap(originalBitmap.getWidth(),
                    originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            // the kept subject region is drawn as an offscreen layer so the
            // DST_IN multiplication only affects it and the transparent
            // base stays empty (the PersonSegEngine remove-mode pattern,
            // there is no opaque base layer for a transparent PNG result)
            int saveCount = canvas.saveLayer(0, 0, originalBitmap.getWidth(),
                    originalBitmap.getHeight(), null);
            try {
                canvas.drawBitmap(originalBitmap, 0, 0, null);
                Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                maskPaint.setXfermode(
                        new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                canvas.drawBitmap(maskBitmap, 0, 0, maskPaint);
            } finally {
                canvas.restoreToCount(saveCount);
            }
            delivered = true;
            return result;
        } finally {
            maskBitmap.recycle();
            // a composite that threw hands no result to the caller
            if (!delivered && result != null) {
                result.recycle();
            }
        }
    }

    /**
     * Convert the CV_8U single channel mask to a white ARGB_8888 bitmap with
     * the mask value as alpha channel, ready to be drawn as a Porter-Duff
     * mask. matToBitmap supports the verified CV_8UC4 &lt;-&gt; ARGB_8888
     * pair, so the single channel mask is merged into an RGBA Mat first. All
     * four merged channels are the mask itself (RGB = alpha = mask value):
     * Android ARGB_8888 bitmaps are premultiplied by default and matToBitmap
     * copies the Mat bytes straight into that premultiplied storage, so
     * merging constant white would produce INVALID premultiplied data
     * (RGB=255 under alpha=0) which the framework composites as full white
     * on every pixel of the draw rect. RGB = alpha is exactly the valid
     * premultiplied representation of white with the mask opacity: it
     * contributes white at alpha strength and nothing where the mask is 0.
     */
    private static Bitmap toMaskBitmap(Mat mask) {
        int width = mask.cols();
        int height = mask.rows();
        Mat rgba = new Mat();
        Bitmap maskBitmap = null;
        boolean delivered = false;
        try {
            // every list handed to an org.opencv API must be a real
            // java.util.ArrayList instance (see EfficientSamProcessor: the
            // OpenCV 5 JNI list handling aborts under CheckJNI on an
            // Arrays.asList view)
            List<Mat> rgbaChannels = new ArrayList<>();
            rgbaChannels.add(mask);
            rgbaChannels.add(mask);
            rgbaChannels.add(mask);
            rgbaChannels.add(mask);
            Core.merge(rgbaChannels, rgba);
            maskBitmap = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgba, maskBitmap);
            delivered = true;
            return maskBitmap;
        } finally {
            rgba.release();
            // a mask bitmap no caller received must not linger until the
            // next GC
            if (!delivered && maskBitmap != null) {
                maskBitmap.recycle();
            }
        }
    }

    /**
     * The compositing reads the mask pixels aligned with the bitmap pixels,
     * a dimension mismatch would produce a shifted or clipped mask silently
     */
    private static void checkMask(Mat mask, Bitmap originalBitmap) {
        if (mask.cols() != originalBitmap.getWidth()
                || mask.rows() != originalBitmap.getHeight()) {
            throw new IllegalArgumentException("Mask dimensions ("
                    + mask.cols() + "x" + mask.rows()
                    + ") must match the bitmap dimensions ("
                    + originalBitmap.getWidth() + "x" + originalBitmap.getHeight() + ")");
        }
    }
}
