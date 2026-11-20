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
 * Instrumented tests of {@link NafNetProcessor}, driving the engine cache's
 * ONE app-lifetime processor instance ({@link MLEngineInstance#getNafNetProcessor()}
 * — per the teardown-poison note on {@link NafNetProcessor} nothing here
 * releases it in-suite; the provider dispose at process end is the only
 * release). Proves the preview path against the official example pair (PSNR
 * floor + R/B channel guard), tiled ≈ single-shot, repeat same-net forwards
 * across shape changes, and the cap-vs-quality sweep backing
 * {@link NafNetProcessor#suggestedWorkingCap(Context)} (heap/quality logged
 * under "NafNetMem"). Fixtures photo_restore_blur_input/expected.jpg are the
 * official OpenCV NAFNet example pair, vendored TEST-ONLY; the suite runs
 * in connectedCheck (the model auto-downloads via {@link ModelTestHelper}).
 * <p>
 * COLD FIRST FORWARD LAW: on OpenCV 5.1.0 x86_64 a net's cold first
 * forward below ~224 px SIGSEGVs inside TBB (measured 16/96/128 crash,
 * 224/288/384/512 pass); a first forward at &gt;= 224 px makes later small
 * forwards safe on the same net — order-independence comes from
 * MLEngineInstance's 512-px synthetic CREATION warm-up (see
 * {@link NafNetProcessor#warmUp()}), the suite adds no warm-up of its own.
 */
@RunWith(AndroidJUnit4.class)
public class NafNetProcessorTest {

    /**
     * Floor, not a quality target (the reference impl measures 25.6 dB at 512 px)
     */
    private static final double NAFNET_PSNR_FLOOR = 12.0;
    /**
     * Tiled vs single-shot sanity floor (same net, only border context and blend differ)
     */
    private static final double TILED_PSNR_FLOOR = 20.0;
    /**
     * Side of the small shape-change input (a few dozen MB of forward RSS)
     */
    private static final int SMALL_INPUT_SIDE = 128;
    /**
     * Working caps swept (the suggestedWorkingCap thresholds)
     */
    private static final int[] CAP_SWEEP = {512, 384, 288, 224};
    /**
     * Logcat tag of the heap/quality instrumentation
     */
    private static final String MEM_TAG = "NafNetMem";

    private static Provider mMLEngineProvider;
    private static ILogger mLogger;
    private static Context mContext;
    /**
     * The ONE cached processor of this process, never released in-suite
     */
    private static NafNetProcessor mProcessor;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider, ModelType.NAFNET_DEBLUR);
        mLogger = mMLEngineProvider.get(ILogger.class);
        mContext = mMLEngineProvider.getContext();
        // the engine cache creates and holds the single processor instance
        // (the app-lifetime policy); every test below drives this instance.
        // Order-independence comes from the CREATION warm-up: the getter's
        // mapping lambda runs one 512-px synthetic forward (see
        // NafNetProcessor.warmUp), so the suite deliberately adds no
        // warm-up of its own — this call IS the warm-up
        mProcessor = mMLEngineProvider.get(MLEngineInstance.class)
                .getNafNetProcessor();
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the provider dispose cascade releases the cached processor at
        // process end — the only legal release under the app-lifetime
        // policy, the suite never releases it any earlier
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
     * Preview path vs the official pair at 512 px (PSNR floor + R/B-swap
     * channel guard — a channel-order bug would invert the ordering).
     */
    @Test
    public void nafnetProcessor_deblurMatchesOfficialOutput() {
        logHeap("deblurMatches start");
        Bitmap input = null;
        Bitmap expected = null;
        Bitmap result = null;
        Bitmap resultAligned = null;
        try {
            input = decodeRaw(R.raw.photo_restore_blur_input);
            expected = decodeRaw(R.raw.photo_restore_blur_expected);
            logHeap("deblurMatches before forward (512px working res)");
            result = mProcessor.process(input, 512);
            logHeap("deblurMatches after forward");
            assertEquals("output must keep the input width",
                    input.getWidth(), result.getWidth());
            assertEquals("output must keep the input height",
                    input.getHeight(), result.getHeight());
            // the jpg ground truth can round to dimensions one pixel off the
            // inference result, scale the result onto the expected grid so
            // the comparison never measures a shifted frame (a no-op when
            // the dims already match, as with this pair)
            resultAligned = result;
            if (expected.getWidth() != result.getWidth()
                    || expected.getHeight() != result.getHeight()) {
                resultAligned = Bitmap.createScaledBitmap(result,
                        expected.getWidth(), expected.getHeight(), true);
            }
            // the channel-order guard: the correct RGB order must beat the
            // deliberately R/B-swapped comparison, a swap bug anywhere in
            // the pipeline would invert that ordering
            double psnrNormal = psnr(resultAligned, expected);
            double psnrSwapped = psnr(resultAligned, expected, true);
            assertTrue("deblur validation (PSNR normal=" + psnrNormal
                            + ", RB-swapped=" + psnrSwapped
                            + ", floor=" + NAFNET_PSNR_FLOOR + ")",
                    psnrNormal >= NAFNET_PSNR_FLOOR
                            && psnrNormal > psnrSwapped);
        } finally {
            if (resultAligned != null && resultAligned != result) {
                resultAligned.recycle();
            }
            if (result != null) {
                result.recycle();
            }
            if (expected != null) {
                expected.recycle();
            }
            if (input != null) {
                input.recycle();
            }
        }
    }

    /**
     * Tiled save path must approximate the single-shot preview (same net,
     * only tile border context and blend differ).
     */
    @Test
    public void nafnetProcessor_tiledMatchesSingleShot() {
        logHeap("tiledMatches start");
        Bitmap source = null;
        Bitmap input = null;
        Bitmap singleShot = null;
        Bitmap tiled = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            input = Bitmap.createScaledBitmap(source, SMALL_INPUT_SIDE,
                    SMALL_INPUT_SIDE, true);
            // cap == longest side: the single shot runs at the native
            // 128x128 (no downscale), the tiled path decomposes the same
            // input into 4 overlapping tiles (2x2 grid, step 80, the edge
            // tiles padded by replication)
            singleShot = mProcessor.process(input, SMALL_INPUT_SIDE);
            tiled = mProcessor.processTiled(input, 96, 16, null);
            assertEquals("tiled output must keep the input width",
                    input.getWidth(), tiled.getWidth());
            assertEquals("tiled output must keep the input height",
                    input.getHeight(), tiled.getHeight());
            assertEquals("single-shot output must keep the input width",
                    input.getWidth(), singleShot.getWidth());
            assertEquals("single-shot output must keep the input height",
                    input.getHeight(), singleShot.getHeight());
            double psnrValue = psnr(singleShot, tiled);
            assertTrue("tiled output must approximate the single-shot"
                    + " output, PSNR=" + psnrValue + " (floor="
                    + TILED_PSNR_FLOOR + ")",
                    psnrValue >= TILED_PSNR_FLOOR);
        } finally {
            if (tiled != null) {
                tiled.recycle();
            }
            if (singleShot != null) {
                singleShot.recycle();
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
     * App-lifetime regression: same-net forwards across a shape change
     * (128 px -&gt; fixture@512 -&gt; 128 px) must all succeed with exact dims.
     */
    @Test
    public void nafnetProcessor_repeatCycles() {
        logHeap("repeatCycles start");
        Bitmap source = null;
        Bitmap input128 = null;
        Bitmap output1 = null;
        Bitmap output2 = null;
        Bitmap output3 = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            input128 = Bitmap.createScaledBitmap(source, SMALL_INPUT_SIDE,
                    SMALL_INPUT_SIDE, true);
            // forward 1: small shape
            logHeap("repeatCycles before forward 1 (128px)");
            output1 = mProcessor.process(input128, SMALL_INPUT_SIDE);
            logHeap("repeatCycles after forward 1");
            assertNotNull("forward 1 produced no output", output1);
            assertEquals("forward 1 must keep the input width",
                    input128.getWidth(), output1.getWidth());
            assertEquals("forward 1 must keep the input height",
                    input128.getHeight(), output1.getHeight());
            // forward 2: the big shape (fixture at the 512 px working cap)
            logHeap("repeatCycles before forward 2 (512px working res)");
            output2 = mProcessor.process(source, 512);
            logHeap("repeatCycles after forward 2");
            assertNotNull("forward 2 produced no output", output2);
            assertEquals("forward 2 must keep the input width",
                    source.getWidth(), output2.getWidth());
            assertEquals("forward 2 must keep the input height",
                    source.getHeight(), output2.getHeight());
            // forward 3: back to the small shape on the same net
            logHeap("repeatCycles before forward 3 (128px)");
            output3 = mProcessor.process(input128, SMALL_INPUT_SIDE);
            logHeap("repeatCycles after forward 3");
            assertNotNull("forward 3 produced no output", output3);
            assertEquals("forward 3 must keep the input width",
                    input128.getWidth(), output3.getWidth());
            assertEquals("forward 3 must keep the input height",
                    input128.getHeight(), output3.getHeight());
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
            if (input128 != null) {
                input128.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * Cap-vs-quality table: PSNR at every suggested cap, logged under
     * NafNetMem; only the 512 entry is asserted (floor + R/B guard).
     */
    @Test
    public void nafnetProcessor_qualityCapSweep() {
        logHeap("qualityCapSweep start");
        Bitmap source = null;
        Bitmap expected = null;
        try {
            source = decodeRaw(R.raw.photo_restore_blur_input);
            expected = decodeRaw(R.raw.photo_restore_blur_expected);
            for (int cap : CAP_SWEEP) {
                Bitmap result = null;
                try {
                    logHeap("qualityCapSweep before forward cap=" + cap);
                    result = mProcessor.process(source, cap);
                    logHeap("qualityCapSweep after forward cap=" + cap);
                    assertEquals("output must keep the input width",
                            source.getWidth(), result.getWidth());
                    assertEquals("output must keep the input height",
                            source.getHeight(), result.getHeight());
                    double psnrNormal = psnr(result, expected);
                    Log.i(MEM_TAG, "quality cap=" + cap
                            + " psnr=" + psnrNormal);
                    if (cap == 512) {
                        // the channel-order guard at the flagship cap: the
                        // correct RGB order must beat the deliberately
                        // R/B-swapped comparison, a swap bug anywhere in
                        // the pipeline would invert that ordering
                        double psnrSwapped = psnr(result, expected, true);
                        assertTrue("deblur validation at cap 512 (PSNR"
                                        + " normal=" + psnrNormal
                                        + ", RB-swapped=" + psnrSwapped
                                        + ", floor=" + NAFNET_PSNR_FLOOR
                                        + ")",
                                psnrNormal >= NAFNET_PSNR_FLOOR
                                        && psnrNormal > psnrSwapped);
                    }
                } finally {
                    if (result != null) {
                        result.recycle();
                    }
                }
            }
        } finally {
            if (expected != null) {
                expected.recycle();
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
}
