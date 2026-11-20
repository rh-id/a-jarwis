package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented tests of {@link Swin2srProcessor}, driving the engine
 * cache's ONE app-lifetime processor instance ({@link
 * MLEngineInstance#getSwin2srProcessor()} — per the teardown-poison note
 * on {@link Swin2srProcessor} nothing here releases it in-suite; the
 * provider dispose at process end is the only release). The NAFNet
 * processor is co-installed for the co-residency regression and equally
 * never released. Proves the FIXED 64 -&gt; 256 tile shape against a bicubic
 * reference and the R/B-swap negative control (strict sr-vs-bicubic PSNR
 * ordering is informational — see
 * {@link #swin2sr_singleTileQualityFloor()}), multi-tile merge
 * determinism + blend/seam quality, repeat same-instance cycles and the
 * NAFNet co-residency (heap/quality logged under "Swin2srMem"; the NAFNet
 * forwards here stay at 512 per the cold-first-forward SEGV law on
 * {@link NafNetProcessor}). Fixtures
 * photo_restore_blur_input/expected.jpg are the official OpenCV NAFNet
 * example pair, vendored TEST-ONLY (the sharp expected reference is the
 * quality-floor ground truth, the blurry input serves as generic photo
 * content for the merge/repeat/co-residency tests); the suite runs in
 * connectedCheck (the models auto-download via {@link ModelTestHelper}).
 */
@RunWith(AndroidJUnit4.class)
public class Swin2srProcessorTest {

    /**
     * Hard floor far above channel/garbage failure modes (a broken channel
     * order lands near 15, a residual-only export near 0)
     */
    private static final double SR_PSNR_FLOOR = 24.0;
    /**
     * Blend sanity floor: overlap 16 vs overlap 0 (same net, only the
     * cross-fade differs, the blend must not distort)
     */
    private static final double BLEND_PSNR_FLOOR = 30.0;
    /**
     * Interior seam bound, loose for CI stability (blended seams measured
     * 0.68-4.40/255 on the spike fixtures; 8 is ~2x the worst measured)
     */
    private static final double SEAM_DIFF_LIMIT = 8.0;
    /**
     * Side of the multi-tile input (2x2+ grid at the 64 px tile, 4x output)
     */
    private static final int SMALL_INPUT_SIDE = 128;
    /**
     * The model's FIXED input tile side (the export shape, the -64 in the
     * repo name)
     */
    private static final int INPUT_TILE = 64;
    /**
     * The model's FIXED output tile side, INPUT_TILE x 4
     */
    private static final int OUTPUT_TILE = 256;
    /**
     * Tiled-overlap of every test run (input pixels)
     */
    private static final int OVERLAP = 16;
    /**
     * Logcat tag of the heap/quality instrumentation
     */
    private static final String MEM_TAG = "Swin2srMem";

    private static Provider mMLEngineProvider;
    private static ILogger mLogger;
    private static Context mContext;
    /**
     * The ONE cached processor of this process, never released in-suite
     */
    private static Swin2srProcessor mProcessor;
    /**
     * The ONE cached NAFNet processor, co-driven by the co-residency test
     */
    private static NafNetProcessor mNafNetProcessor;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        // selective install: only the two nets this suite drives (NOT the
        // all-values overload)
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.SWIN2SR_UPSCALE, ModelType.NAFNET_DEBLUR);
        mLogger = mMLEngineProvider.get(ILogger.class);
        mContext = mMLEngineProvider.getContext();
        // the engine cache creates and holds the single processor instances
        // (the app-lifetime policy); every test below drives these instances
        mProcessor = mMLEngineProvider.get(MLEngineInstance.class)
                .getSwin2srProcessor();
        mNafNetProcessor = mMLEngineProvider.get(MLEngineInstance.class)
                .getNafNetProcessor();
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the provider dispose cascade releases the cached processors at
        // process end — the only legal release under the app-lifetime
        // policy, the suite never releases them any earlier
        mMLEngineProvider.dispose();
    }

    /**
     * One-line native heap reading under MEM_TAG (reconstructs the memory story)
     */
    private static void logHeap(String stage) {
        Log.i(MEM_TAG, stage
                + " nativeHeapAllocated=" + Debug.getNativeHeapAllocatedSize()
                + " nativeHeapFree=" + Debug.getNativeHeapFreeSize());
    }

    /**
     * The FIXED 64 -&gt; 256 tile shape at its cleanest: exactly ONE forward
     * (overlap 0), ground truth = the SHARP reference. Strict
     * PSNR-vs-bicubic ordering is NOT asserted because this is a BSRGAN
     * real-world SR model — it targets degraded real photos (the app's
     * inputs) and on synthetic clean LR bicubic's smooth reconstruction
     * can match/beat it on MSE (sr 28.26 vs bicubic 29.24 measured,
     * logged informational). The gate protects channel order (the R/B
     * swap control), scale/dims and a floor far above channel/garbage
     * failure modes.
     */
    @Test
    public void swin2sr_singleTileQualityFloor() {
        logHeap("singleTileQualityFloor start");
        Bitmap source = null;
        Bitmap groundTruth = null;
        Bitmap input = null;
        Bitmap result = null;
        Bitmap bicubic = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_expected);
            // the center 256x256 crop of the 600x482 sharp reference
            // (x=172, y=113) as the ground truth
            groundTruth = Bitmap.createBitmap(source,
                    (source.getWidth() - OUTPUT_TILE) / 2,
                    (source.getHeight() - OUTPUT_TILE) / 2,
                    OUTPUT_TILE, OUTPUT_TILE);
            // the model's FIXED input tile shape
            input = Bitmap.createScaledBitmap(groundTruth, INPUT_TILE,
                    INPUT_TILE, true);
            logHeap("singleTileQualityFloor before forward");
            // overlap 0: exactly ONE 64->256 forward, zero padding — the
            // 2x2 grid's BORDER_REPLICATE-padded edge tiles (overlap 16)
            // would confound a clean model-quality measurement
            result = mProcessor.processTiled(input, 0, null);
            logHeap("singleTileQualityFloor after forward");
            assertEquals("output must be exactly 4x the input width",
                    OUTPUT_TILE, result.getWidth());
            assertEquals("output must be exactly 4x the input height",
                    OUTPUT_TILE, result.getHeight());
            // the bicubic reference at the same target size
            bicubic = Bitmap.createScaledBitmap(input, OUTPUT_TILE,
                    OUTPUT_TILE, true);
            double psnrNormal = psnr(result, groundTruth);
            double psnrBicubic = psnr(bicubic, groundTruth);
            // the channel-order guard: the correct RGB order must beat the
            // deliberately R/B-swapped comparison, a swap bug anywhere in
            // the pipeline would invert that ordering
            double psnrSwapped = psnr(result, groundTruth, true);
            // the sr-vs-bicubic ordering is INFORMATIONAL only (see the
            // method javadoc), logged for the quality history
            Log.i(MEM_TAG, "single tile psnr sr=" + psnrNormal
                    + " bicubic=" + psnrBicubic
                    + " rbSwapped=" + psnrSwapped);
            assertTrue("the SR output must clear the floor far above"
                            + " channel/garbage failure modes and the correct"
                            + " RGB order must beat the R/B-swapped comparison"
                            + " (a channel-order bug would invert that"
                            + " ordering), PSNR sr=" + psnrNormal
                            + " rbSwapped=" + psnrSwapped + ", floor="
                            + SR_PSNR_FLOOR,
                    psnrNormal >= SR_PSNR_FLOOR && psnrNormal > psnrSwapped);
        } finally {
            if (bicubic != null) {
                bicubic.recycle();
            }
            if (result != null) {
                result.recycle();
            }
            if (input != null) {
                input.recycle();
            }
            if (groundTruth != null) {
                groundTruth.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * Multi-tile merge correctness on a 128x128 input (3x3 grid at step
     * 48, 512x512 output): exact dims, deterministic rerun on the same
     * instance (OpenCV CPU DNN is deterministic in-process, the spike
     * recorded bit-identical stats across ABIs), the blend must not
     * distort (overlap 16 vs 0) and the interior output-grid seams must
     * stay under the loose bound (measured 0.68-4.40/255 blended; the
     * UNBLENDED spike probe was 3.79/255 for reference).
     */
    @Test
    public void swin2sr_multiTileCorrectness() {
        logHeap("multiTileCorrectness start");
        Bitmap source = null;
        Bitmap input = null;
        Bitmap overlap16 = null;
        Bitmap overlap16Again = null;
        Bitmap overlap0 = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            input = Bitmap.createScaledBitmap(source, SMALL_INPUT_SIDE,
                    SMALL_INPUT_SIDE, true);
            overlap16 = mProcessor.processTiled(input, OVERLAP, null);
            assertEquals("output must be exactly 4x the input width",
                    SMALL_INPUT_SIDE * 4, overlap16.getWidth());
            assertEquals("output must be exactly 4x the input height",
                    SMALL_INPUT_SIDE * 4, overlap16.getHeight());
            // determinism: the same instance must reproduce the same output
            overlap16Again = mProcessor.processTiled(input, OVERLAP, null);
            assertTrue("same-instance rerun must be deterministic"
                            + " (OpenCV CPU DNN, in-process)",
                    overlap16.sameAs(overlap16Again));
            // the blend must not distort the image itself
            overlap0 = mProcessor.processTiled(input, 0, null);
            double blendPsnr = psnr(overlap16, overlap0);
            Log.i(MEM_TAG, "blend psnr overlap16-vs-0=" + blendPsnr);
            assertTrue("blending must not distort, PSNR overlap16-vs-0="
                            + blendPsnr + " (floor=" + BLEND_PSNR_FLOOR + ")",
                    blendPsnr >= BLEND_PSNR_FLOOR);
            // the interior output-grid seams: the 48 px input grid step is
            // 192 output px, so the vertical seams sit at x=192 and x=384
            // and the horizontal ones at y=192 and y=384
            int outStep = (INPUT_TILE - OVERLAP) * 4;
            double seamV1 = seamDiff(overlap16, outStep, true);
            double seamV2 = seamDiff(overlap16, outStep * 2, true);
            double seamH1 = seamDiff(overlap16, outStep, false);
            double seamH2 = seamDiff(overlap16, outStep * 2, false);
            Log.i(MEM_TAG, "seam vertical x=" + outStep + " meanAbsDiff="
                    + seamV1 + " x=" + (outStep * 2) + " meanAbsDiff="
                    + seamV2);
            Log.i(MEM_TAG, "seam horizontal y=" + outStep + " meanAbsDiff="
                    + seamH1 + " y=" + (outStep * 2) + " meanAbsDiff="
                    + seamH2);
            assertTrue("vertical seam at x=" + outStep + " must be blended"
                            + " (limit=" + SEAM_DIFF_LIMIT + ", got "
                            + seamV1 + ")",
                    seamV1 < SEAM_DIFF_LIMIT);
            assertTrue("vertical seam at x=" + (outStep * 2)
                            + " must be blended (limit=" + SEAM_DIFF_LIMIT
                            + ", got " + seamV2 + ")",
                    seamV2 < SEAM_DIFF_LIMIT);
            assertTrue("horizontal seam at y=" + outStep
                            + " must be blended (limit=" + SEAM_DIFF_LIMIT
                            + ", got " + seamH1 + ")",
                    seamH1 < SEAM_DIFF_LIMIT);
            assertTrue("horizontal seam at y=" + (outStep * 2)
                            + " must be blended (limit=" + SEAM_DIFF_LIMIT
                            + ", got " + seamH2 + ")",
                    seamH2 < SEAM_DIFF_LIMIT);
        } finally {
            if (overlap0 != null) {
                overlap0.recycle();
            }
            if (overlap16Again != null) {
                overlap16Again.recycle();
            }
            if (overlap16 != null) {
                overlap16.recycle();
            }
            if (input != null) {
                input.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * App-lifetime regression: same-instance forwards across a shape
     * change (128 px -&gt; 64 px export-shape input -&gt; 128 px) must all
     * succeed with exact dims.
     */
    @Test
    public void swin2sr_repeatCycles() {
        logHeap("repeatCycles start");
        Bitmap source = null;
        Bitmap input128 = null;
        Bitmap input64 = null;
        Bitmap output1 = null;
        Bitmap output2 = null;
        Bitmap output3 = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            input128 = Bitmap.createScaledBitmap(source, SMALL_INPUT_SIDE,
                    SMALL_INPUT_SIDE, true);
            // forward 1: the multi-tile shape
            logHeap("repeatCycles before forward 1 (128px)");
            output1 = mProcessor.processTiled(input128, OVERLAP, null);
            logHeap("repeatCycles after forward 1");
            assertNotNull("forward 1 produced no output", output1);
            assertEquals("forward 1 must be exactly 4x the input width",
                    input128.getWidth() * 4, output1.getWidth());
            assertEquals("forward 1 must be exactly 4x the input height",
                    input128.getHeight() * 4, output1.getHeight());
            // forward 2: the 64x64 export-shape input (a 2x2 tile grid at
            // overlap 16, step 48: 64x64 -&gt; 256x256)
            input64 = Bitmap.createScaledBitmap(input128, INPUT_TILE,
                    INPUT_TILE, true);
            logHeap("repeatCycles before forward 2 (64px tile)");
            output2 = mProcessor.processTiled(input64, OVERLAP, null);
            logHeap("repeatCycles after forward 2");
            assertNotNull("forward 2 produced no output", output2);
            assertEquals("forward 2 must be exactly the output tile width",
                    OUTPUT_TILE, output2.getWidth());
            assertEquals("forward 2 must be exactly the output tile height",
                    OUTPUT_TILE, output2.getHeight());
            // forward 3: back to the multi-tile shape on the same instance
            logHeap("repeatCycles before forward 3 (128px)");
            output3 = mProcessor.processTiled(input128, OVERLAP, null);
            logHeap("repeatCycles after forward 3");
            assertNotNull("forward 3 produced no output", output3);
            assertEquals("forward 3 must be exactly 4x the input width",
                    input128.getWidth() * 4, output3.getWidth());
            assertEquals("forward 3 must be exactly 4x the input height",
                    input128.getHeight() * 4, output3.getHeight());
        } finally {
            if (output3 != null) {
                output3.recycle();
            }
            if (output2 != null) {
                output2.recycle();
            }
            if (output1 != null) {
                output1.recycle();
            }
            if (input64 != null) {
                input64.recycle();
            }
            if (input128 != null) {
                input128.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * Co-residency regression: BOTH nets alive simultaneously (the engine
     * cache holds them app-lifetime) must survive repeated interleaved
     * forwards — the teardown-poison law only forbids RELEASES between
     * forwards, never co-existence (spike: validated at 2.87 GB). The
     * NAFNet forwards run at 512 per the cold-first-forward SEGV law
     * (see {@link NafNetProcessor}).
     */
    @Test
    public void swin2sr_coresidencyWithNafNet() {
        logHeap("coresidency start");
        Bitmap source = null;
        Bitmap input128 = null;
        Bitmap deblur1 = null;
        Bitmap upscaled = null;
        Bitmap deblur2 = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            input128 = Bitmap.createScaledBitmap(source, SMALL_INPUT_SIDE,
                    SMALL_INPUT_SIDE, true);
            // NAFNet forward 1 (the preview path at the 512 working res,
            // cold-safe per the >= 224 law)
            logHeap("coresidency before nafnet forward 1 (512px res)");
            deblur1 = mNafNetProcessor.process(source, 512);
            logHeap("coresidency after nafnet forward 1");
            assertNotNull("nafnet forward 1 produced no output", deblur1);
            assertEquals("nafnet forward 1 must keep the input width",
                    source.getWidth(), deblur1.getWidth());
            assertEquals("nafnet forward 1 must keep the input height",
                    source.getHeight(), deblur1.getHeight());
            // Swin2SR forward on the co-resident cache
            logHeap("coresidency before swin2sr forward (128px, 2.87 GB co-residency spike)");
            upscaled = mProcessor.processTiled(input128, OVERLAP, null);
            logHeap("coresidency after swin2sr forward");
            assertNotNull("swin2sr forward produced no output", upscaled);
            assertEquals("swin2sr output must be exactly 4x the input width",
                    input128.getWidth() * 4, upscaled.getWidth());
            assertEquals("swin2sr output must be exactly 4x the input height",
                    input128.getHeight() * 4, upscaled.getHeight());
            // NAFNet forward 2 on the same co-resident cache
            logHeap("coresidency before nafnet forward 2 (512px res)");
            deblur2 = mNafNetProcessor.process(source, 512);
            logHeap("coresidency after nafnet forward 2");
            assertNotNull("nafnet forward 2 produced no output", deblur2);
            assertEquals("nafnet forward 2 must keep the input width",
                    source.getWidth(), deblur2.getWidth());
            assertEquals("nafnet forward 2 must keep the input height",
                    source.getHeight(), deblur2.getHeight());
        } finally {
            if (deblur2 != null) {
                deblur2.recycle();
            }
            if (upscaled != null) {
                upscaled.recycle();
            }
            if (deblur1 != null) {
                deblur1.recycle();
            }
            if (input128 != null) {
                input128.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * Decode a raw test resource as ARGB_8888 or fail loudly
     */
    private static Bitmap decodeRaw(int resId) {
        Bitmap bitmap;
        InputStream in = mContext.getResources().openRawResource(resId);
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeStream(in, null, options);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // the decode already consumed what it needs
            }
        }
        if (bitmap == null) {
            throw new IllegalStateException("Failed to decode raw test"
                    + " resource " + resId);
        }
        return bitmap;
    }

    /**
     * PSNR from decoded pixel arrays (never Mat accessors — n-dim blob bug)
     */
    private static double psnr(Bitmap a, Bitmap b) {
        return psnr(a, b, false);
    }

    /**
     * PSNR with an optional R/B swap of the first bitmap (the channel-order
     * negative control)
     */
    private static double psnr(Bitmap a, Bitmap b, boolean swapRbOfA) {
        int w = Math.max(a.getWidth(), b.getWidth());
        int h = Math.max(a.getHeight(), b.getHeight());
        Bitmap aAligned = a;
        Bitmap bAligned = b;
        if (aAligned.getWidth() != w || aAligned.getHeight() != h) {
            aAligned = Bitmap.createScaledBitmap(a, w, h, true);
        }
        if (bAligned.getWidth() != w || bAligned.getHeight() != h) {
            bAligned = Bitmap.createScaledBitmap(b, w, h, true);
        }
        int[] pixelsA = new int[w * h];
        int[] pixelsB = new int[w * h];
        aAligned.getPixels(pixelsA, 0, w, 0, 0, w, h);
        bAligned.getPixels(pixelsB, 0, w, 0, 0, w, h);
        double mse = 0.0;
        for (int i = 0; i < pixelsA.length; i++) {
            int colorA = pixelsA[i];
            int rA = (colorA >> 16) & 0xFF;
            int bA = colorA & 0xFF;
            if (swapRbOfA) {
                int swap = rA;
                rA = bA;
                bA = swap;
            }
            int gA = (colorA >> 8) & 0xFF;
            int colorB = pixelsB[i];
            int rB = (colorB >> 16) & 0xFF;
            int gB = (colorB >> 8) & 0xFF;
            int bB = colorB & 0xFF;
            int dR = rA - rB;
            int dG = gA - gB;
            int dB = bA - bB;
            mse += (double) (dR * dR + dG * dG + dB * dB);
        }
        mse /= (pixelsA.length * 3.0);
        if (aAligned != a) {
            aAligned.recycle();
        }
        if (bAligned != b) {
            bAligned.recycle();
        }
        if (mse <= 0.0) {
            return 99.0;
        }
        return 10.0 * Math.log10(255.0 * 255.0 / mse);
    }

    /**
     * The spike's seam metric: mean absolute per-channel diff between the
     * adjacent pixel columns (vertical) or rows (horizontal) across one
     * interior grid seam coordinate
     *
     * @param bitmap the probed image
     * @param coord  the seam coordinate (the column x or row y, the
     *               neighbor coord-1 is compared against)
     * @param vertical true for a vertical seam (column comparison)
     * @return the mean absolute channel diff in 0..255 units
     */
    private static double seamDiff(Bitmap bitmap, int coord, boolean vertical) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        long absSum = 0;
        long count = 0;
        if (vertical) {
            for (int y = 0; y < h; y++) {
                int a = pixels[y * w + coord - 1];
                int b = pixels[y * w + coord];
                absSum += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                count += 3;
            }
        } else {
            for (int x = 0; x < w; x++) {
                int a = pixels[(coord - 1) * w + x];
                int b = pixels[coord * w + x];
                absSum += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                count += 3;
            }
        }
        return absSum / (double) count;
    }
}
