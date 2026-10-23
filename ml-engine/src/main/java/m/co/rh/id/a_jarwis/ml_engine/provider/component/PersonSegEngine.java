package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Portrait segmentation engine of the PP-HumanSeg model, enables two portrait
 * edit modes on an arbitrary photo: blur its background (JPEG result) or
 * remove its background (transparent PNG result). The person is segmented
 * once and kept as a soft alpha mask, the two modes composite the photo over
 * different base layers with the same mask.
 * <p>
 * The processing is a heavy native operation, callers are expected to run it
 * serialized (e.g. on a single background executor).
 */
public class PersonSegEngine {
    private static final String TAG = "PersonSegEngine";
    /**
     * Minimum Gaussian blur kernel size, OpenCV requires a positive odd kernel size
     */
    private static final int BLUR_KERNEL_SIZE_MIN = 3;
    /**
     * A mask (fully opaque pixel sum) covering less than this fraction of the
     * image area is treated as "no person detected", it absorbs false positives
     * of the model that segment only a few scattered regions
     */
    private static final double MIN_PERSON_AREA_RATIO = 0.002;

    private final ILogger mLogger;
    private final ProviderValue<MLEngineInstance> mMLEngine;
    /**
     * Lazy mask cache keyed by an opaque cache key (e.g. an image index of an
     * editor batch), a key without a cached entry is segmented on first
     * read, improving the repeated display rebuild of the same image on
     * config toggles (the inference is not re-run)
     */
    private final ConcurrentHashMap<Object, PersonSegMask> mMaskCache;

    public PersonSegEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
        mMaskCache = new ConcurrentHashMap<>();
    }

    /**
     * Compute the soft alpha mask of the person in the given bitmap without
     * using the cache.
     * The mask has the same dimensions as the input bitmap, pixel value = the
     * probability that the pixel belongs to the person (0 background,
     * 255 person, values in between at the feathered segmentation edge).
     *
     * @param bitmap bitmap to be segmented, never recycled by this method
     * @return the mask handle, the caller owns it and must release() it
     */
    public PersonSegMask computeMask(Bitmap bitmap) {
        return new PersonSegMask(mMLEngine.get().getPersonSegProcessor().computeMask(bitmap));
    }

    /**
     * Compute the soft alpha mask of the person in the given bitmap through the
     * per-key cache: the first read of a key without a cached mask runs the
     * segmentation, later reads of the same key reuse the cached mask until it
     * is released (see releaseMask), so repeated display rebuilds of the same
     * image on config toggles never re-run the inference.
     *
     * @param cacheKey opaque key identifying the mask (e.g. the image index
     *                 in a batch of picked images)
     * @param bitmap   bitmap to be segmented, never recycled by this method
     * @return the cached mask handle, owned by the engine until releaseMask
     */
    public PersonSegMask getMask(Object cacheKey, Bitmap bitmap) {
        PersonSegMask mask = mMaskCache.get(cacheKey);
        if (mask == null) {
            mask = computeMask(bitmap);
            // two reads of the same key can segment concurrently (e.g. two
            // images changed close together), putIfAbsent keeps the first
            // mask and the loser copy is discarded
            PersonSegMask cachedMask = mMaskCache.putIfAbsent(cacheKey, mask);
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
     * Release the cached mask of the given cache key, a later read of that key
     * (e.g. the same image revisited) re-segments it. Keys without a cached
     * mask are ignored.
     *
     * @param cacheKey key possibly holding a cached mask
     */
    public void releaseMask(Object cacheKey) {
        PersonSegMask mask = mMaskCache.remove(cacheKey);
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
     * Check whether the given mask contains a person, so engines depending on
     * a person to be present can report "no person detected" instead of
     * compositing an empty edit result.
     *
     * @param mask person mask created by computeMask or getMask
     * @return true when the masked area (sum of opaque pixel values) covers
     * at least {@link #MIN_PERSON_AREA_RATIO} of the bitmap area
     */
    public boolean hasPerson(PersonSegMask mask) {
        return hasPersonImpl(mask.mMask);
    }

    /**
     * Person detection of the raw native mask Mat, package-private so the
     * processor level helpers of this package reuse the exact computation
     */
    static boolean hasPersonImpl(Mat mask) {
        if (mask.empty()) {
            return false;
        }
        double area = (double) mask.cols() * mask.rows();
        double sum = Core.sumElems(mask).val[0];
        return sum >= area * 255.0 * MIN_PERSON_AREA_RATIO;
    }

    /**
     * Process the bitmap by compositing a Gaussian blur everywhere except the
     * person region, the person stays sharp above the blurred background.
     * The blur kernel is first computed from the bitmap min dimension at full
     * resolution (see FaceEngine#computeFullKernelSize sharing the face blur
     * look per strength), then scaled down by the given scale factor, so a
     * preview render visually matches the saved full resolution result.
     *
     * @param originalBitmap bitmap to be processed, never recycled by this method
     * @param mask           person mask created by computeMask or getMask
     *                       of the same bitmap, must match the bitmap dimensions,
     *                       this method does not release the mask
     * @param config         blur strength configuration, null falls back to the
     *                       default configuration
     * @param scale          preview to full resolution scale factor,
     *                       1.0 means the bitmap is full resolution
     * @return a copy of the originalBitmap with the background blurred opaque
     * (ARGB_8888) for a JPEG export
     */
    public Bitmap blurBackground(Bitmap originalBitmap, PersonSegMask mask,
                                 BlurConfig config, float scale) {
        checkMask(mask.mMask, originalBitmap);
        Bitmap blurredBackground =
                createBlurredBitmap(originalBitmap, config, scale);
        try {
            return compositeMaskedPerson(originalBitmap, blurredBackground, mask.mMask);
        } finally {
            blurredBackground.recycle();
        }
    }

    /**
     * Process the bitmap by making its background fully transparent, the person
     * region keeps the original pixels with the mask as alpha channel.
     *
     * @param originalBitmap bitmap to be processed, never recycled by this method
     * @param mask           person mask created by computeMask or getMask
     *                       of the same bitmap, must match the bitmap dimensions,
     *                       this method does not release the mask
     * @return a copy of the originalBitmap with only the person kept
     * (ARGB_8888) for a transparent PNG export
     */
    public Bitmap removeBackground(Bitmap originalBitmap, PersonSegMask mask) {
        checkMask(mask.mMask, originalBitmap);
        return compositeMaskedPerson(originalBitmap, null, mask.mMask);
    }

    /**
     * Composite the person region of the originalBitmap (its pixels multiplied
     * by the mask alpha) over the given base layer: a blurred background copy
     * for the blur mode, or nothing for the transparent remove mode.
     */
    private static Bitmap compositeMaskedPerson(Bitmap originalBitmap,
                                                Bitmap baseBitmap, Mat mask) {
        Bitmap maskBitmap = toMaskBitmap(mask);
        try {
            Bitmap result = Bitmap.createBitmap(originalBitmap.getWidth(),
                    originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            if (baseBitmap != null) {
                canvas.drawBitmap(baseBitmap, 0, 0, null);
            }
            // the masked person region is drawn as an offscreen layer so the
            // DST_IN multiplication only affects the person layer, not the base
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
            return result;
        } finally {
            maskBitmap.recycle();
        }
    }

    /**
     * Gaussian blur of the whole bitmap, used as the base layer beneath the
     * sharp person. The kernel mirrors FaceEngine#blurFaceCrop: computed from
     * the full resolution min dimension per blur strength, then scaled down to
     * this bitmap resolution and forced to a positive odd value.
     */
    private Bitmap createBlurredBitmap(Bitmap originalBitmap,
                                       BlurConfig config, float scale) {
        BlurConfig blurConfig = config == null ? new BlurConfig() : config;
        if (scale <= 0f) {
            // a failed dimension decode falls back to a full resolution blur
            scale = 1f;
        }
        int minDim = Math.min(originalBitmap.getWidth(), originalBitmap.getHeight());
        // the preview min dimension back-scaled to the full resolution min dimension
        int fullMinDim = Math.round(minDim / scale);
        int fullKernelSize = FaceEngine.computeFullKernelSize(
                fullMinDim, blurConfig.getStrength());
        // scale the full resolution kernel down to this bitmap resolution
        int kernelSize = Math.round(fullKernelSize * scale);
        if (kernelSize < BLUR_KERNEL_SIZE_MIN) {
            kernelSize = BLUR_KERNEL_SIZE_MIN;
        }
        if (kernelSize % 2 == 0) {
            kernelSize++;
        }
        Mat blurred = new Mat();
        Utils.bitmapToMat(originalBitmap, blurred);
        try {
            Imgproc.GaussianBlur(blurred, blurred,
                    new Size(kernelSize, kernelSize),
                    kernelSize * FaceEngine.BLUR_SIGMA_SCALE);
            Bitmap result = Bitmap.createBitmap(originalBitmap.getWidth(),
                    originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(blurred, result);
            return result;
        } finally {
            blurred.release();
        }
    }

    /**
     * Convert the CV_8U single channel mask to a white ARGB_8888 bitmap with the
     * mask value as alpha channel, ready to be drawn as a Porter-Duff mask.
     * matToBitmap supports the verified CV_8UC4 &lt;-&gt; ARGB_8888 pair, so the
     * single channel mask is merged into a white RGBA Mat first.
     */
    private static Bitmap toMaskBitmap(Mat mask) {
        int width = mask.cols();
        int height = mask.rows();
        Mat white = new Mat(height, width, CvType.CV_8U, new Scalar(255));
        Mat rgba = new Mat();
        try {
            Core.merge(Arrays.asList(white, white, white, mask), rgba);
            Bitmap maskBitmap = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgba, maskBitmap);
            return maskBitmap;
        } finally {
            white.release();
            rgba.release();
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
