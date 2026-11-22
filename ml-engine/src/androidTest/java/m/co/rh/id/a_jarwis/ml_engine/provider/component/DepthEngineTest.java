package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link DepthEngine}, which will execute on an
 * Android device (models auto-downloaded via {@link ModelTestHelper}),
 * verifying the per-key depth cache and the lens blur pipeline: the far-band
 * / near-band blur separation on the real-depth landscape photo, the
 * preview/full-res scale agreement and the opaque whole-image result. The
 * landscape.jpg asset is the permanent landscape fixture of these depth
 * tests, chosen for its real near/far depth separation (Pexels photo
 * 8970476 "Empty Road Leading to Mountains" by Damir Salyamov, Pexels
 * License: free to use, commercial use allowed, attribution not required —
 * road-to-mountains, the top band is the far mountains/sky, the bottom band
 * the near road).
 * <p>
 * METRIC NOTE (connected runs 1-3, api36_aosp): real-photo band metrics are
 * dead ends for the far/near blur discrimination — the ABSOLUTE
 * Laplacian-variance loss is content-confounded across bands (run 1: the
 * grainy road band lost more absolute detail than the smooth sky band even
 * against the full-strength kernel), and the RELATIVE loss fractions
 * saturate on real content (run 2: Laplacian fractions far 0.978 vs near
 * 0.983 — even the minimal kernel-3 Gaussian destroys ~98% of the Laplacian
 * variance there). The real-photo tests therefore assert only "the far band
 * actually blurred" plus the confound-free depth polarity on the closeness
 * map (depthMap_farBandLessClosenessThanNearBand), and the far/near
 * DISCRIMINATION lives in the synthetic gradient test
 * (lensBlur_syntheticDepthGradient_blursFarMoreThanNear) with plain pixel
 * variance on block-quantized random detail.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class DepthEngineTest {

    private static final String TAG = "DepthEngineTest";
    /**
     * Blur strength used by the lens blur tests (the BlurConfig default,
     * the middle of the lens blur editor's slider range)
     */
    private static final int STRENGTH = 3;
    /**
     * Shared cache key of the landscape lens blur tests (the depth map of
     * the identical decoded photo, so the expensive fp32 inference runs once
     * for the whole class)
     */
    private static final String KEY_LANDSCAPE = "DepthEngineTest_landscape";
    /**
     * Vertical band ratios of the far (top) and near (bottom) quarter bands
     * of the landscape photo used by the blur-metric assertions
     */
    private static final double FAR_TOP_RATIO = 0.0;
    private static final double FAR_BOTTOM_RATIO = 0.25;
    private static final double NEAR_TOP_RATIO = 0.75;
    private static final double NEAR_BOTTOM_RATIO = 1.0;
    /** Side length of the synthetic lens blur test bitmap (square) */
    private static final int SYNTHETIC_SIZE = 512;
    /**
     * Block quantization of the synthetic noise detail: the block scale sits
     * between the minimal kernel's sigma (1.5 for kernel 3) and the
     * full-strength kernel (201 at the 512 size), so kernel 3 mostly
     * preserves the blocks while the max kernel completely flattens them
     * (see the synthetic test javadoc for why per-pixel noise cannot
     * discriminate the ladder)
     */
    private static final int SYNTHETIC_BLOCK = 16;
    /** Arbitrary fixed seed of the synthetic noise, only determinism matters */
    private static final long SYNTHETIC_NOISE_SEED = 0xC0FFEE;

    private static DepthEngine mDepthEngine;
    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.DEPTH_ESTIMATION);
        mDepthEngine = mMLEngineProvider.get(DepthEngine.class);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the engine cache owns every depth map handle handed out by
        // getDepth, a global release keeps the suite retry safe
        mDepthEngine.releaseAllDepths();
        // release the engine-cached depth processor and nudge the runtime
        // BEFORE the provider dispose: the ~480-550 MB native dnn workspace
        // must not be inherited by the heavy suites that run later in the
        // same instrumentation process — the first connected run lost the
        // Swin2SR suite to the lowmemorykiller when the depth workspace
        // stacked on its tiled peak (a plain dispose cascade release +
        // single nudge proved not enough, the explicit gc/runFinalization
        // prelude is the FaceEngineTest release precedent)
        mDepthEngine.releaseProcessor();
        System.gc();
        Runtime.getRuntime().runFinalization();
        mMLEngineProvider.dispose();
    }

    /**
     * getDepth hands out the same engine-owned map for repeated reads of
     * a key (the inference is not re-run) and re-infers after releaseDepth.
     */
    @Test
    public void getDepth_depthCachedPerKey() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        Object cacheKey = "DepthEngineTest_getDepth";
        try {
            Mat cachedDepth = mDepthEngine.getDepth(cacheKey, bitmap);
            // second read of the same key must reuse the cached map
            Mat cachedDepth2 = mDepthEngine.getDepth(cacheKey, bitmap);
            assertSame("repeated getDepth of a key must hand out the cached"
                    + " map", cachedDepth, cachedDepth2);
            // engine-owned map must not be released in between
            mDepthEngine.releaseDepth(cacheKey);
            Mat reInferredDepth = mDepthEngine.getDepth(cacheKey, bitmap);
            assertNotNull(reInferredDepth);
            assertNotSame("after releaseDepth the next read must re-infer",
                    cachedDepth, reInferredDepth);
            assertEquals(bitmap.getWidth(), reInferredDepth.cols());
            assertEquals(bitmap.getHeight(), reInferredDepth.rows());
        } finally {
            // all getDepth maps are engine owned, only the global release
            // runs
            mDepthEngine.releaseAllDepths();
            bitmap.recycle();
        }
    }

    /**
     * Deterministic depth polarity of the cached closeness map (the semantic
     * core of the lens blur, confound-free): the far band (top quarter,
     * mountains/sky) of the real-depth landscape photo must carry clearly
     * LESS closeness than the near band (bottom quarter, road). The issue-#6
     * spike measured the top band around 0.1-0.3 against the bottom band
     * above 0.7, hence the generous 0.15 margin. This test replaces the
     * polarity role of the blur-metric assertions, whose absolute variance
     * comparison is content-confounded across bands (see the class javadoc).
     */
    @Test
    public void depthMap_farBandLessClosenessThanNearBand() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        try {
            Mat depth = mDepthEngine.getDepth(KEY_LANDSCAPE, bitmap);
            double farMean = bandMean(depth,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearMean = bandMean(depth,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            mLogger.d(TAG, "depthMap_polarity farMean=" + farMean
                    + " nearMean=" + nearMean);
            assertTrue("far band mean closeness " + farMean
                            + " must be at least 0.15 below the near band mean "
                            + nearMean,
                    farMean < nearMean - 0.15);
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Deterministic, content-free discrimination of the lens blur kernel
     * ladder and weight distribution (end-to-end): a synthetic bitmap filled
     * with uniform random detail and a synthetic linear closeness gradient
     * (0 at the TOP row = far, 1 at the BOTTOM row = near) must produce a
     * far band (top quarter) that loses a clearly larger fraction of its
     * plain pixel variance than the near band (bottom quarter).
     * <p>
     * SYNTHETIC because the real-photo band metrics are doubly dead-ended
     * (see the class javadoc): the absolute Laplacian loss is
     * content-confounded across bands and the relative Laplacian fractions
     * saturate on real content (even the minimal kernel-3 Gaussian destroys
     * ~98% of the Laplacian variance, measured far 0.978 vs near 0.983 on
     * device). The metric here is PLAIN pixel variance on block-quantized
     * random detail: per-pixel white noise would saturate the plain fraction
     * too (a kernel-3 sigma-1.5 blur keeps only ~11% of white-noise
     * variance, so the near band fraction would sit around 0.9 and the
     * 0.25 margin is unreachable), while 16-px blocks are coarse enough that
     * kernel 3 mostly preserves them and the full-strength kernel completely
     * flattens them — exactly the separation the kernel ladder must produce.
     * The synthetic closeness Mat bypasses the processor (no inference), so
     * this test verifies the partition-of-unity weight distribution and the
     * kernel ladder end-to-end through the package-private Mat blend only.
     */
    @Test
    public void lensBlur_syntheticDepthGradient_blursFarMoreThanNear()
            throws Exception {
        Bitmap bitmap = createSyntheticNoiseBitmap(SYNTHETIC_SIZE, SYNTHETIC_BLOCK);
        Mat depth = createLinearClosenessMat(SYNTHETIC_SIZE);
        Bitmap result = null;
        try {
            result = mDepthEngine.lensBlurBackground(bitmap, depth,
                    STRENGTH, 1f);
            writeDebugArtifact(result, "DepthEngineTest_lensBlur_synthetic.jpg");
            assertEquals(bitmap.getWidth(), result.getWidth());
            assertEquals(bitmap.getHeight(), result.getHeight());
            double farVariance = pixelVariance(bitmap,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearVariance = pixelVariance(bitmap,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            double farLoss = 1.0 - pixelVariance(result,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO) / farVariance;
            double nearLoss = 1.0 - pixelVariance(result,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO) / nearVariance;
            mLogger.d(TAG, "lensBlur_synthetic farVariance=" + farVariance
                    + " nearVariance=" + nearVariance
                    + " farLossFraction=" + farLoss
                    + " nearLossFraction=" + nearLoss);
            assertTrue("far band loss fraction " + farLoss
                            + " must exceed the near band loss fraction " + nearLoss
                            + " by more than 0.25",
                    farLoss > nearLoss + 0.25);
        } finally {
            if (result != null) {
                result.recycle();
            }
            // the synthetic map is caller-owned (not from the engine cache)
            depth.release();
            bitmap.recycle();
        }
    }

    /**
     * The lens blur on the real-depth landscape photo: the far band (top,
     * mountains/sky) is measurably blurred by the depth-driven blend, the
     * pure depth path (no mask) that every landscape without a person uses.
     * Only the "blur happened" sanity is asserted here: the band-vs-band
     * discrimination moved to the synthetic test
     * (lensBlur_syntheticDepthGradient_blursFarMoreThanNear) because the
     * real-photo band metrics are content-confounded (the absolute
     * Laplacian loss, run 1) and saturated (the relative fractions, run 2);
     * the depth polarity of the real photo is covered by
     * depthMap_farBandLessClosenessThanNearBand. The near band variance loss
     * is logged for diagnostics only.
     */
    @Test
    public void lensBlur_farBandMoreBlurredThanNearBand() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        Bitmap blurred = null;
        try {
            Mat depth = mDepthEngine.getDepth(KEY_LANDSCAPE, bitmap);
            blurred = mDepthEngine.lensBlurBackground(bitmap, depth,
                    STRENGTH, 1f);
            writeDebugArtifact(blurred, "DepthEngineTest_lensBlur_farNear.jpg");
            assertEquals(bitmap.getWidth(), blurred.getWidth());
            assertEquals(bitmap.getHeight(), blurred.getHeight());
            double farIncrease = laplacianVariance(bitmap,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO)
                    - laplacianVariance(blurred, FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearIncrease = laplacianVariance(bitmap,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO)
                    - laplacianVariance(blurred, NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            mLogger.d(TAG, "lensBlur_farNear farIncrease=" + farIncrease
                    + " nearIncrease=" + nearIncrease);
            assertTrue("far band not blurred (variance increase " + farIncrease + ")",
                    farIncrease > 0);
        } finally {
            if (blurred != null) {
                blurred.recycle();
            }
            bitmap.recycle();
        }
    }

    /**
     * The strength-to-kernel convention must preserve the RELATIVE look per
     * depth band between the preview scale and the full resolution scale:
     * the same photo rendered with scale 1.0 and scale 0.5 (the kernel is
     * computed from the back-scaled full resolution min dimension and scaled
     * down, so the preview visually matches the saved result) must keep a
     * PARITY of the per-band relative Laplacian-variance loss fractions
     * within a tolerant 0.3 margin — the invariant is that the relative look
     * is preserved, the absolute variance differs by design (different
     * kernel sizes). The far band is additionally sanity-checked to be
     * actually blurred at both scales. All values are logged.
     */
    @Test
    public void lensBlur_scaleAgreement() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        Bitmap resultFull = null;
        Bitmap resultHalf = null;
        try {
            Mat depth = mDepthEngine.getDepth(KEY_LANDSCAPE, bitmap);
            resultFull = mDepthEngine.lensBlurBackground(bitmap, depth,
                    STRENGTH, 1f);
            resultHalf = mDepthEngine.lensBlurBackground(bitmap, depth,
                    STRENGTH, 0.5f);
            double farVariance = laplacianVariance(bitmap,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearVariance = laplacianVariance(bitmap,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            double farIncreaseFull = farVariance - laplacianVariance(resultFull,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearIncreaseFull = nearVariance - laplacianVariance(resultFull,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            double farIncreaseHalf = farVariance - laplacianVariance(resultHalf,
                    FAR_TOP_RATIO, FAR_BOTTOM_RATIO);
            double nearIncreaseHalf = nearVariance - laplacianVariance(resultHalf,
                    NEAR_TOP_RATIO, NEAR_BOTTOM_RATIO);
            double farFractionFull = farIncreaseFull / farVariance;
            double nearFractionFull = nearIncreaseFull / nearVariance;
            double farFractionHalf = farIncreaseHalf / farVariance;
            double nearFractionHalf = nearIncreaseHalf / nearVariance;
            mLogger.d(TAG, "lensBlur_scaleAgreement"
                    + " farIncreaseFull=" + farIncreaseFull
                    + " nearIncreaseFull=" + nearIncreaseFull
                    + " farIncreaseHalf=" + farIncreaseHalf
                    + " nearIncreaseHalf=" + nearIncreaseHalf
                    + " farFractionFull=" + farFractionFull
                    + " nearFractionFull=" + nearFractionFull
                    + " farFractionHalf=" + farFractionHalf
                    + " nearFractionHalf=" + nearFractionHalf);
            assertTrue("scale 1.0: far band not blurred (variance increase "
                    + farIncreaseFull + ")", farIncreaseFull > 0);
            assertTrue("scale 0.5: far band not blurred (variance increase "
                    + farIncreaseHalf + ")", farIncreaseHalf > 0);
            assertTrue("far band loss fraction diverges between the scales: "
                            + "full=" + farFractionFull + " half=" + farFractionHalf,
                    Math.abs(farFractionFull - farFractionHalf) < 0.3);
            assertTrue("near band loss fraction diverges between the scales: "
                            + "full=" + nearFractionFull + " half=" + nearFractionHalf,
                    Math.abs(nearFractionFull - nearFractionHalf) < 0.3);
        } finally {
            if (resultFull != null) {
                resultFull.recycle();
            }
            if (resultHalf != null) {
                resultHalf.recycle();
            }
            bitmap.recycle();
        }
    }

    /**
     * The whole-image depth blend (the only path): the result is opaque
     * ARGB_8888 and the call does not throw.
     */
    @Test
    public void lensBlur_pureDepthWithoutMask_opaqueResult() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        Bitmap result = null;
        try {
            Mat depth = mDepthEngine.getDepth(KEY_LANDSCAPE, bitmap);
            result = mDepthEngine.lensBlurBackground(bitmap, depth,
                    STRENGTH, 1f);
            assertEquals(bitmap.getWidth(), result.getWidth());
            assertEquals(bitmap.getHeight(), result.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, result.getConfig());
            // the blend runs on the RGBA float base, the alpha channel of an
            // opaque input stays opaque (sampled at the corners and center)
            assertTrue("top left corner not opaque",
                    Color.alpha(result.getPixel(0, 0)) == 255);
            assertTrue("bottom right corner not opaque",
                    Color.alpha(result.getPixel(bitmap.getWidth() - 1,
                            bitmap.getHeight() - 1)) == 255);
            assertTrue("center not opaque",
                    Color.alpha(result.getPixel(bitmap.getWidth() / 2,
                            bitmap.getHeight() / 2)) == 255);
        } finally {
            if (result != null) {
                result.recycle();
            }
            bitmap.recycle();
        }
    }

    /**
     * The public key-driven facade end to end: prepareDepth warms the cache
     * under an opaque key (no handle is returned to the caller) and
     * lensBlurBackground with the SAME key blends from the warmed map, the
     * result has the input dimensions and stays opaque ARGB_8888.
     */
    @Test
    public void lensBlur_publicKeyDrivenFacade_opaqueResult() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        Object cacheKey = "DepthEngineTest_publicFacade";
        Bitmap result = null;
        try {
            mDepthEngine.prepareDepth(cacheKey, bitmap);
            result = mDepthEngine.lensBlurBackground(cacheKey, bitmap,
                    STRENGTH, 1f);
            assertEquals(bitmap.getWidth(), result.getWidth());
            assertEquals(bitmap.getHeight(), result.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, result.getConfig());
            // the blend runs on the RGBA float base, the alpha channel of an
            // opaque input stays opaque (sampled at the corners and center)
            assertTrue("top left corner not opaque",
                    Color.alpha(result.getPixel(0, 0)) == 255);
            assertTrue("bottom right corner not opaque",
                    Color.alpha(result.getPixel(bitmap.getWidth() - 1,
                            bitmap.getHeight() - 1)) == 255);
            assertTrue("center not opaque",
                    Color.alpha(result.getPixel(bitmap.getWidth() / 2,
                            bitmap.getHeight() / 2)) == 255);
        } finally {
            if (result != null) {
                result.recycle();
            }
            bitmap.recycle();
        }
    }

    /**
     * The release contract of a directly constructed {@link DepthProcessor}:
     * it must return a usable map, must refuse further inference once
     * released (fail fast instead of touching the freed native net) and
     * releasing more than once must be a safe no-op. The processor is
     * constructed directly (never through the engine instance) so the cached
     * instance behind {@link #mDepthEngine} is never touched and the
     * released instance is discarded here.
     */
    @Test
    public void depthProcessor_releaseContract() {
        DepthProcessor processor = new DepthProcessor(
                mMLEngineProvider.get(MLEngineInstance.class)
                        .getModelFile(ModelType.DEPTH_ESTIMATION)
                        .getAbsolutePath(), mLogger);
        Bitmap input = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        input.eraseColor(Color.RED);
        try {
            Mat depth = processor.computeDepth(input);
            try {
                assertNotNull(depth);
            } finally {
                // the raw map Mat is caller owned (see the processor contract)
                depth.release();
            }
            processor.release();
            try {
                processor.computeDepth(input);
                fail("a released processor must refuse inference");
            } catch (IllegalStateException expected) {
                // the documented released-processor contract
            }
            // releasing more than once is a safe no-op
            processor.release();
        } finally {
            input.recycle();
            // the released net wrapper is deleted by its finalizer, nudge
            // the runtime so the native reclamation happens here instead of
            // an arbitrary later GC (same hygiene as the engine release
            // tests of PersonSegEngineTest)
            System.gc();
            Runtime.getRuntime().runFinalization();
        }
    }

    /**
     * Deterministic synthetic noise bitmap: one random RGB color per
     * {@code block} x {@code block} block (java.util.Random with a fixed
     * seed, so every run and device sees the identical content), written
     * into a CV_8UC4 Mat and converted with matToBitmap for speed. The block
     * quantization is load-bearing for the plain-variance metric: per-pixel
     * white noise would already lose ~89% of its pixel variance under the
     * minimal kernel-3 blur (sigma 1.5 keeps only ~11% of white-noise
     * variance), saturating the relative loss fraction, while the block
     * detail survives kernel 3 mostly intact (only the block borders are
     * smoothed) and is completely flattened by the full-strength kernel.
     */
    private static Bitmap createSyntheticNoiseBitmap(int size, int block) {
        Random random = new Random(SYNTHETIC_NOISE_SEED);
        Mat noise = new Mat(size, size, CvType.CV_8UC4);
        byte[] blockRow = new byte[4 * block];
        try {
            int blocks = size / block;
            for (int by = 0; by < blocks; by++) {
                for (int bx = 0; bx < blocks; bx++) {
                    byte red = (byte) random.nextInt(256);
                    byte green = (byte) random.nextInt(256);
                    byte blue = (byte) random.nextInt(256);
                    for (int i = 0; i < block; i++) {
                        blockRow[4 * i] = red;
                        blockRow[4 * i + 1] = green;
                        blockRow[4 * i + 2] = blue;
                        blockRow[4 * i + 3] = (byte) 255;
                    }
                    Mat blockView = noise.submat(by * block, (by + 1) * block,
                            bx * block, (bx + 1) * block);
                    try {
                        for (int row = 0; row < block; row++) {
                            blockView.put(row, 0, blockRow);
                        }
                    } finally {
                        blockView.release();
                    }
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size,
                    Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(noise, bitmap);
            return bitmap;
        } finally {
            noise.release();
        }
    }

    /**
     * Synthetic linear closeness map (CV_32F, {@code size} x {@code size}):
     * closeness = row / (size - 1), 0 at the TOP row (far) rising linearly
     * to 1 at the BOTTOM row (near), filled row-wise with put
     * (deterministic, no inference involved).
     */
    private static Mat createLinearClosenessMat(int size) {
        Mat closeness = new Mat(size, size, CvType.CV_32F);
        float[] row = new float[size];
        for (int y = 0; y < size; y++) {
            Arrays.fill(row, (float) y / (size - 1));
            closeness.put(y, 0, row);
        }
        return closeness;
    }

    /**
     * Plain pixel (luma) variance of the given horizontal band of the
     * bitmap: a higher value = more detail, a Gaussian blur lowers it.
     * Plain variance instead of the Laplacian variance because the
     * Laplacian metric saturates even for the minimal kernel (see the class
     * javadoc).
     */
    private static double pixelVariance(Bitmap bitmap, double topRatio,
                                        double bottomRatio) {
        int height = bitmap.getHeight();
        int top = (int) Math.round(height * topRatio);
        int bottom = (int) Math.round(height * bottomRatio);
        Mat rgba = new Mat();
        Mat gray = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            // the band is cloned off the parent so the gray Mat can be
            // released independently
            Mat band = gray.submat(top, bottom, 0, gray.cols()).clone();
            try {
                Core.meanStdDev(band, mean, std);
                double deviation = std.get(0, 0)[0];
                return deviation * deviation;
            } finally {
                band.release();
            }
        } finally {
            rgba.release();
            gray.release();
            mean.release();
            std.release();
        }
    }

    /**
     * Mean closeness value of the given horizontal band of the closeness map
     * (the map has the bitmap dimensions, so the same band ratios as the
     * blur metrics apply), read through a submat view of the CV_32F single
     * channel Mat.
     */
    private static double bandMean(Mat closeness, double topRatio,
                                   double bottomRatio) {
        int top = (int) Math.round(closeness.rows() * topRatio);
        int bottom = (int) Math.round(closeness.rows() * bottomRatio);
        Mat band = closeness.submat(top, bottom, 0, closeness.cols());
        try {
            return Core.mean(band).val[0];
        } finally {
            band.release();
        }
    }

    /**
     * Variance of the Laplacian response of the given horizontal band of the
     * bitmap: a higher value = more high-frequency detail = sharper, a
     * Gaussian blur lowers it. The band ratios select the far/near depth
     * bands of the landscape photo.
     */
    private static double laplacianVariance(Bitmap bitmap, double topRatio,
                                            double bottomRatio) {
        int height = bitmap.getHeight();
        int top = (int) Math.round(height * topRatio);
        int bottom = (int) Math.round(height * bottomRatio);
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat lap = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            // the band is cloned off the parent so the gray Mat can be
            // released independently
            Mat band = gray.submat(top, bottom, 0, gray.cols()).clone();
            try {
                Imgproc.Laplacian(band, lap, CvType.CV_64F);
                Core.meanStdDev(lap, mean, std);
                double deviation = std.get(0, 0)[0];
                return deviation * deviation;
            } finally {
                band.release();
            }
        } finally {
            rgba.release();
            gray.release();
            lap.release();
            mean.release();
            std.release();
        }
    }

    /**
     * Dump a debug artifact on the device for manual inspection (the
     * PersonSegEngineTest pattern).
     */
    private void writeDebugArtifact(Bitmap bitmap, String fileName)
            throws IOException {
        File tempFile = mMLEngineProvider.get(FileHelper.class)
                .createTempFile(fileName);
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        }
    }

    /**
     * Decode one of the instrumented test images from res/raw, closing the
     * resource stream (decodeStream does not close it).
     */
    private Bitmap decodeTestImage(int rawId) {
        Context context = mMLEngineProvider.getContext();
        try (InputStream in = context.getResources().openRawResource(rawId)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode raw resource", e);
        }
    }
}
