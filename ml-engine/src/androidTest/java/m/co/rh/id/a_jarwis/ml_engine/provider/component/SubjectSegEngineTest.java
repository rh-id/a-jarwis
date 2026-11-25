package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.IOException;

import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link SubjectSegEngine}, which will execute on an
 * Android device (model auto-downloaded via {@link ModelTestHelper}),
 * verifying the subject gate, the per-key mask cache, the transparent
 * removeBackground compositing of both paths and the release/recreate path
 * of the session-lifetime processor. The synthetic input is the
 * deterministic subject-on-contrast-background composition proven to
 * segment in the issue-#4 SPIKE #2 device runs (no bundled photo needed).
 * <p>
 * The mask cache is proved BEHAVIORALLY: no mask handle exists anymore (the
 * mask never leaves the engine), so the cache is observed through the
 * composites alone — two bitmaps with their subjects in diagonally opposite
 * quadrants, so "composited through my own mask" and "composited through
 * the other bitmap's mask" differ at every probe.
 * <p>
 * The {@link org.junit.FixMethodOrder} NAME_ASCENDING order only matters
 * when the class runs in a single instrumentation process (the
 * MagicEraserTest pattern): the release/recreate test runs LAST — it frees
 * the cached processor mid-test and re-parses the model through the
 * engine's bounded native-heap settle, so it must not stack its second
 * parse under any other test's live workspace on the memory-constrained
 * tiers. Under Android Test Orchestrator (the :ml-engine default) every
 * test runs in its own process and the order is harmless.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SubjectSegEngineTest {

    private static final String TAG = "SubjectSegEngineTest";
    /**
     * The native-heap ceiling of the test-local reclaim wait (mirrors the
     * engine settle ceiling, see
     * MLEngineInstance.SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES)
     */
    private static final long RECLAIM_WAIT_CEILING_BYTES =
            600L * 1024L * 1024L;
    private static final long RECLAIM_WAIT_TIMEOUT_MS = 5_000;
    /**
     * Half size (pixels) of the square alpha probe block of the per-key
     * cache test, a block instead of a single pixel so one stray pixel of
     * a feathered edge can never hide behind a probe
     */
    private static final int PROBE_HALF_SIZE = 16;
    /**
     * Exclusive lower bound of "the subject is kept": the subject center
     * probe of the compositing test, shared by the subject probe blocks of
     * the cache test
     */
    private static final int KEPT_SUBJECT_ALPHA_EXCLUSIVE = 200;
    /**
     * Normalized frame position of the subject plateau probe of the
     * top-left quadrant subject bitmap (see
     * {@link #buildQuadrantSubjectBitmap}): deep inside its own subject
     * rectangle, and at the same time deep inside the plain gradient
     * background of the diagonally opposite bottom-right variant (the two
     * subjects never overlap), so the same probe separates "composited
     * through my own mask" (opaque) from "composited through the other
     * bitmap's mask" (transparent)
     */
    private static final float TOP_LEFT_SUBJECT_X = 0.15f;
    private static final float TOP_LEFT_SUBJECT_Y = 0.38f;
    /**
     * The same probe of the bottom-right quadrant subject bitmap (the 180
     * degree rotation of the top-left layout around the frame centre)
     */
    private static final float BOTTOM_RIGHT_SUBJECT_X = 0.85f;
    private static final float BOTTOM_RIGHT_SUBJECT_Y = 0.62f;

    private static SubjectSegEngine mSubjectSegEngine;
    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider, ModelType.SUBJECT_SEG);
        mSubjectSegEngine = mMLEngineProvider.get(SubjectSegEngine.class);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // release the session masks and the engine-cached processor and
        // nudge the runtime BEFORE the provider dispose: the ~1 GB native
        // dnn workspace must not be inherited by the heavy suites that run
        // later in the same instrumentation process (the DepthProcessorTest
        // hygiene pattern)
        mSubjectSegEngine.releaseAllMasks();
        mSubjectSegEngine.releaseProcessor();
        System.gc();
        Runtime.getRuntime().runFinalization();
        mMLEngineProvider.dispose();
    }

    /**
     * prepareSubject is the subject gate of the caller: true for a bitmap
     * with a salient subject, false for the blank bitmap — its model
     * output is flat, so the flat guard rejects it reliably (the shapeless
     * subject-less inputs — uniform gray, smooth gradients — are rejected
     * by the processor's raw-signal contrast gate instead, see
     * SubjectSegProcessor#normalizeSalience). The one-shot
     * removeBackground of the full resolution save/share export applies the
     * same gate and returns null for the blank bitmap.
     */
    @Test
    public void prepareSubject_reportsSubjectGate() {
        Bitmap bitmap = buildSubjectBitmap(1024, 1024);
        // a blank bitmap: every pixel is zero (a fully transparent black
        // frame), nothing salient to find
        Bitmap blankBitmap = Bitmap.createBitmap(1024, 1024,
                Bitmap.Config.ARGB_8888);
        try {
            assertTrue("synthetic subject image must contain a subject",
                    mSubjectSegEngine.prepareSubject(
                            "SubjectSegEngineTest_subject", bitmap));
            assertFalse("a blank image must not count as a subject",
                    mSubjectSegEngine.prepareSubject(
                            "SubjectSegEngineTest_blank", blankBitmap));
            assertNull("a bitmap without a subject must not be composited"
                            + " into a fully transparent result",
                    mSubjectSegEngine.removeBackground(blankBitmap));
        } finally {
            mSubjectSegEngine.releaseAllMasks();
            blankBitmap.recycle();
            bitmap.recycle();
        }
    }

    /**
     * The per-key cache, observed through the composites alone (the mask is
     * engine-internal): removeBackground under an already warmed key must
     * composite through the CACHED mask, never through a mask of the bitmap
     * it was handed. Bitmap A (subject in the top-left quadrant) warms the
     * key, so bitmap B (subject in the diagonally opposite bottom-right
     * quadrant) comes out with B's OWN subject transparent and A's subject
     * area kept — the opposite of what B's own mask would produce. On this
     * path the cached composite returns in tens of milliseconds against
     * seconds for a segmentation (measured ~35 ms vs ~3 s on api36_aosp,
     * evidence only — timing is never asserted).
     * <p>
     * The same is proven the other way round for the prepareSubject warm-up
     * (the editor's segmentation → display flow): once B is prepared under
     * the key, the composite of A follows B's mask. After releaseMask the
     * very same call re-segments and follows the bitmap's own mask again.
     */
    @Test
    public void removeBackground_cachesMaskPerKey() {
        Bitmap bitmapA = buildQuadrantSubjectBitmap(1024, 1024, false);
        Bitmap bitmapB = buildQuadrantSubjectBitmap(1024, 1024, true);
        Bitmap resultA = null;
        Bitmap resultB = null;
        Bitmap resultAPrepared = null;
        Bitmap resultBReleased = null;
        Object cacheKey = "SubjectSegEngineTest_removeBackground";
        try {
            // cache miss: A is segmented and its mask cached under the key
            resultA = mSubjectSegEngine.removeBackground(cacheKey, bitmapA);
            assertNotNull(resultA);
            assertSubjectKeptAt(resultA, "A under its own mask (cache miss)",
                    TOP_LEFT_SUBJECT_X, TOP_LEFT_SUBJECT_Y);
            assertBackgroundAt(resultA, "B's subject area under A's mask",
                    BOTTOM_RIGHT_SUBJECT_X, BOTTOM_RIGHT_SUBJECT_Y);

            // CACHE HIT: B is composited through A's cached mask, so B's
            // own subject lies in A's background and must vanish
            resultB = mSubjectSegEngine.removeBackground(cacheKey, bitmapB);
            assertNotNull(resultB);
            assertBackgroundAt(resultB, "B's own subject area under the cached"
                            + " A mask (B's own mask must not have been used)",
                    BOTTOM_RIGHT_SUBJECT_X, BOTTOM_RIGHT_SUBJECT_Y);
            assertSubjectKeptAt(resultB, "A's subject area under the cached"
                    + " A mask", TOP_LEFT_SUBJECT_X, TOP_LEFT_SUBJECT_Y);

            // CACHE HIT on the verdict too: the key still holds A's mask,
            // so prepareSubject reports A's verdict (a subject) and never
            // looks at the blank bitmap it was handed
            Bitmap blankBitmap = Bitmap.createBitmap(1024, 1024,
                    Bitmap.Config.ARGB_8888);
            try {
                assertTrue("a cache hit must report the cached mask's"
                                + " verdict, not the handed bitmap's",
                        mSubjectSegEngine.prepareSubject(cacheKey, blankBitmap));
            } finally {
                blankBitmap.recycle();
            }

            // releaseMask drops the cached mask: preparing B now segments B
            // and caches B's mask under the key
            mSubjectSegEngine.releaseMask(cacheKey);
            assertTrue("B must be found as a subject after the release",
                    mSubjectSegEngine.prepareSubject(cacheKey, bitmapB));

            // CACHE HIT on the prepared mask: A is composited through B's
            // mask, so A's OWN subject must vanish this time
            resultAPrepared = mSubjectSegEngine.removeBackground(cacheKey,
                    bitmapA);
            assertNotNull(resultAPrepared);
            assertBackgroundAt(resultAPrepared, "A's own subject area under"
                            + " the prepared B mask (A's stale mask must not"
                            + " have survived the release)",
                    TOP_LEFT_SUBJECT_X, TOP_LEFT_SUBJECT_Y);
            assertSubjectKeptAt(resultAPrepared, "B's subject area under the"
                    + " prepared B mask", BOTTOM_RIGHT_SUBJECT_X,
                    BOTTOM_RIGHT_SUBJECT_Y);

            // a second releaseMask, then the SAME call that used to follow
            // A's mask re-segments B and follows B's mask again
            mSubjectSegEngine.releaseMask(cacheKey);
            resultBReleased = mSubjectSegEngine.removeBackground(cacheKey,
                    bitmapB);
            assertNotNull(resultBReleased);
            assertSubjectKeptAt(resultBReleased, "B's own subject area after"
                    + " releaseMask", BOTTOM_RIGHT_SUBJECT_X,
                    BOTTOM_RIGHT_SUBJECT_Y);
            assertBackgroundAt(resultBReleased, "A's subject area under B's"
                    + " re-segmented mask", TOP_LEFT_SUBJECT_X,
                    TOP_LEFT_SUBJECT_Y);
        } finally {
            // every cached mask is engine owned, only the global release runs
            mSubjectSegEngine.releaseAllMasks();
            recycle(resultBReleased);
            recycle(resultAPrepared);
            recycle(resultB);
            recycle(resultA);
            bitmapB.recycle();
            bitmapA.recycle();
        }
    }

    /**
     * removeBackground keeps only the subject, on both paths: the result is
     * ARGB_8888, the background corners turn fully transparent while the
     * subject center keeps (nearly) full opacity through the mask, for the
     * transparent PNG export.
     */
    @Test
    public void removeBackground_transparentCornersKeepSubject() {
        Bitmap bitmap = buildSubjectBitmap(1024, 1024);
        Bitmap oneShotResult = null;
        Bitmap keyedResult = null;
        try {
            // one-shot full resolution path (the save/share export)
            oneShotResult = mSubjectSegEngine.removeBackground(bitmap);
            assertNotNull("a bitmap with a subject must be composited",
                    oneShotResult);
            assertSubjectKeptBackgroundTransparent(bitmap, oneShotResult);
            // key-driven path (the editor display, segmented just in time)
            keyedResult = mSubjectSegEngine.removeBackground(
                    "SubjectSegEngineTest_transparentCorners", bitmap);
            assertNotNull("the key-driven composite must never return null",
                    keyedResult);
            assertSubjectKeptBackgroundTransparent(bitmap, keyedResult);
        } finally {
            mSubjectSegEngine.releaseAllMasks();
            recycle(keyedResult);
            recycle(oneShotResult);
            bitmap.recycle();
        }
    }

    /**
     * The session-lifetime release/recreate path: after
     * releaseProcessor the next mask computation transparently re-parses
     * the model through the engine's bounded native-heap settle and works
     * again. Runs LAST (NAME_ASCENDING) so its release + second parse never
     * stacks under another test's live workspace on the memory-constrained
     * tiers (the SPIKE #2 teardown evidence).
     */
    @Test
    public void zReleaseProcessor_recreatesProcessor() {
        Bitmap bitmap = buildSubjectBitmap(1024, 1024);
        Bitmap result = null;
        Object cacheKeyBefore = "SubjectSegEngineTest_beforeRelease";
        Object cacheKeyAfter = "SubjectSegEngineTest_afterRelease";
        try {
            assertTrue("the synthetic subject must be found before the"
                    + " release", mSubjectSegEngine.prepareSubject(
                    cacheKeyBefore, bitmap));
            mSubjectSegEngine.releaseMask(cacheKeyBefore);
            // release the cached processor: the engine must recreate it on
            // demand (session-lifetime release contract)
            mSubjectSegEngine.releaseProcessor();
            System.gc();
            Runtime.getRuntime().runFinalization();
            // mirror of the engine settle: give the finalizer the time the
            // 2 GB tier needs before the re-parse (the engine settle also
            // runs inside getSubjectSegProcessor, this prelude keeps the
            // tiers from entering the forward under the old workspace)
            waitNativeHeapBelow(RECLAIM_WAIT_CEILING_BYTES, RECLAIM_WAIT_TIMEOUT_MS);
            // a cache miss: the mask is computed by the RECREATED processor
            result = mSubjectSegEngine.removeBackground(cacheKeyAfter, bitmap);
            assertNotNull("the recreated processor must still remove the"
                    + " background", result);
            // the recreated processor must segment the subject again
            assertSubjectKeptBackgroundTransparent(bitmap, result);
        } finally {
            mSubjectSegEngine.releaseAllMasks();
            recycle(result);
            bitmap.recycle();
        }
    }

    /**
     * The compositing assertions shared by every path: input dimensions,
     * ARGB_8888, fully transparent background corners and a (nearly) fully
     * opaque subject center.
     */
    private static void assertSubjectKeptBackgroundTransparent(Bitmap bitmap,
                                                               Bitmap result) {
        assertEquals(bitmap.getWidth(), result.getWidth());
        assertEquals(bitmap.getHeight(), result.getHeight());
        // alpha channel must exist for the transparent PNG export
        assertEquals(Bitmap.Config.ARGB_8888, result.getConfig());
        // the background corners are far outside the feathered subject
        // mask: log the actual values and require full transparency
        int[] cornerAlpha = {
                Color.alpha(result.getPixel(0, 0)),
                Color.alpha(result.getPixel(bitmap.getWidth() - 1, 0)),
                Color.alpha(result.getPixel(0, bitmap.getHeight() - 1)),
                Color.alpha(result.getPixel(bitmap.getWidth() - 1,
                        bitmap.getHeight() - 1))
        };
        mLogger.d(TAG, "removeBackground corner alphas: TL=" + cornerAlpha[0]
                + " TR=" + cornerAlpha[1] + " BL=" + cornerAlpha[2]
                + " BR=" + cornerAlpha[3]);
        assertTrue("top left corner still opaque (alpha " + cornerAlpha[0]
                + ")", cornerAlpha[0] == 0);
        assertTrue("top right corner still opaque (alpha " + cornerAlpha[1]
                + ")", cornerAlpha[1] == 0);
        assertTrue("bottom left corner still opaque (alpha "
                + cornerAlpha[2] + ")", cornerAlpha[2] == 0);
        assertTrue("bottom right corner still opaque (alpha "
                + cornerAlpha[3] + ")", cornerAlpha[3] == 0);
        // the subject center keeps (nearly) full opacity
        int centerAlpha = Color.alpha(result.getPixel(
                (int) (bitmap.getWidth() * 0.43f),
                (int) (bitmap.getHeight() * 0.59f)));
        mLogger.d(TAG, "removeBackground subject center alpha: " + centerAlpha);
        assertTrue("subject center became transparent (alpha "
                + centerAlpha + ")",
                centerAlpha > KEPT_SUBJECT_ALPHA_EXCLUSIVE);
    }

    /**
     * Require the probe block at the given normalized frame position of the
     * composite to be fully transparent: the mask the composite went
     * through has pure background there.
     */
    private static void assertBackgroundAt(Bitmap result, String message,
                                           float positionX, float positionY) {
        int[] alphaRange = alphaRange(result, positionX, positionY);
        assertTrue(message + ": expected a fully transparent probe block,"
                        + " alpha range " + alphaRange[0] + ".." + alphaRange[1]
                        + " at (" + positionX + ", " + positionY + ")",
                alphaRange[1] == 0);
    }

    /**
     * Require the probe block at the given normalized frame position of the
     * composite to be opaque subject plateau: every pixel of the block
     * keeps (nearly) full opacity.
     */
    private static void assertSubjectKeptAt(Bitmap result, String message,
                                            float positionX, float positionY) {
        int[] alphaRange = alphaRange(result, positionX, positionY);
        assertTrue(message + ": expected an opaque subject probe block,"
                        + " alpha range " + alphaRange[0] + ".." + alphaRange[1]
                        + " at (" + positionX + ", " + positionY + ")",
                alphaRange[0] > KEPT_SUBJECT_ALPHA_EXCLUSIVE);
    }

    /**
     * Smallest and largest alpha of the square probe block centred on the
     * given normalized frame position, as {min, max}.
     */
    private static int[] alphaRange(Bitmap bitmap, float positionX,
                                    float positionY) {
        int centerX = Math.round(bitmap.getWidth() * positionX);
        int centerY = Math.round(bitmap.getHeight() * positionY);
        int min = 255;
        int max = 0;
        for (int y = centerY - PROBE_HALF_SIZE; y <= centerY + PROBE_HALF_SIZE; y++) {
            for (int x = centerX - PROBE_HALF_SIZE; x <= centerX + PROBE_HALF_SIZE; x++) {
                int alpha = Color.alpha(bitmap.getPixel(x, y));
                min = Math.min(min, alpha);
                max = Math.max(max, alpha);
            }
        }
        return new int[]{min, max};
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }
    }

    /**
     * The deterministic subject-on-contrast-background composition of the
     * SPIKE #2 runs: diagonal gradient background, a solid high-chroma
     * subject rectangle, a bright overlapping circle and a dark top band.
     */
    private static Bitmap buildSubjectBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                Color.rgb(240, 240, 245), Color.rgb(40, 44, 52),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(Color.rgb(210, 60, 40));
        canvas.drawRect(width * 0.18f, height * 0.30f,
                width * 0.68f, height * 0.88f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(width * 0.52f, height * 0.42f,
                width * 0.10f, paint);
        paint.setColor(Color.rgb(20, 20, 24));
        canvas.drawRect(0, 0, width, height * 0.08f, paint);
        return bitmap;
    }

    /**
     * The same ingredients as {@link #buildSubjectBitmap} with the subject
     * confined to ONE quadrant of the frame: the top-left quadrant, or for
     * {@code bottomRight} the 180 degree rotation of that whole layout
     * around the frame centre (subject, circle and dark band), placing the
     * subject in the diagonally opposite bottom-right quadrant. The two
     * variants share the gradient background, so each one's subject sits on
     * the plain background of the other: never overlapping, never near.
     */
    private static Bitmap buildQuadrantSubjectBitmap(int width, int height,
                                                     boolean bottomRight) {
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                Color.rgb(240, 240, 245), Color.rgb(40, 44, 52),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        if (bottomRight) {
            canvas.save();
            canvas.rotate(180, width / 2f, height / 2f);
        }
        // the subject block: the same rectangle + circle arrangement as the
        // SPIKE #2 composition, fitted into the top-left quadrant below the
        // dark band
        paint.setColor(Color.rgb(210, 60, 40));
        canvas.drawRect(width * 0.05f, height * 0.13f,
                width * 0.45f, height * 0.45f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(width * 0.33f, height * 0.22f,
                width * 0.09f, paint);
        paint.setColor(Color.rgb(20, 20, 24));
        canvas.drawRect(0, 0, width, height * 0.08f, paint);
        if (bottomRight) {
            canvas.restore();
        }
        return bitmap;
    }

    /**
     * Bounded reclaim wait (the test-local mirror of the engine settle),
     * see {@link SubjectSegProcessorTest} for the rationale.
     */
    private static void waitNativeHeapBelow(long ceilingBytes, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        long heap = Debug.getNativeHeapAllocatedSize();
        while (heap > ceilingBytes && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
            System.gc();
            Runtime.getRuntime().runFinalization();
            heap = Debug.getNativeHeapAllocatedSize();
        }
    }
}
