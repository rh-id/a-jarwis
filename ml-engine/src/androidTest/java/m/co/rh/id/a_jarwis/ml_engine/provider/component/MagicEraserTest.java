package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * The single instrumented test class of {@link MagicEraserEngine} (one test
 * class per engine component), which will execute on an Android device
 * (models auto-downloaded via {@link ModelTestHelper}). It merged the four
 * former MagicEraser test classes and carries their four concerns:
 * <p>
 * - the engine round-trip: the stroke rasterization, the prompt conversion
 *   and the erase round-trip on synthetic images;
 * - the overlay plumbing: the premultiplied validity of the overlay
 *   conversion (Android ARGB_8888 bitmaps are premultiplied, so the merged
 *   white tint must never exceed its alpha), the in-place feather +
 *   upsample sequence and the no-object contract — pure OpenCV/Bitmap
 *   operations on synthetic masks, no models, no SAM inference (fast and
 *   deterministic, safe on the 32-bit emulator);
 * - the processor release contract of {@link MLEngineInstance}: a released
 *   processor must be removed from the cache so the next accessor lazily
 *   recreates a fresh functional instance (the OpenCV dnn workspace retained
 *   inside the native net must not pin the released processor in place), a
 *   released processor must refuse further inference and releasing must be
 *   idempotent;
 * - the load-and-run spike of the magic eraser models: the assertions are
 *   functional only (the models load, run and return the expected
 *   shapes/content), the latency printed in the SPIKE log lines is judged by
 *   a human from the logs, never by a threshold.
 * <p>
 * The NAME_ASCENDING method order is deliberate, it is a memory contract for
 * the memory-constrained CI emulator and not a cosmetic preference:
 * <p>
 * - computeObjectMask_segmentsRedSquare runs FIRST so the SAM memory
 *   diagnostic gets a clean no-net baseline (nothing else has loaded a net
 *   when its before-SAM heap snapshot is taken);
 * - inpaint_fillsSquareRegion (the LaMa-only spike) runs after the erase
 *   tests whose finally blocks released their nets, so its LaMa forward
 *   starts from an emptied processor cache on the 32-bit emulator;
 * - releaseEfficientSamProcessor_thenRegetYieldsFunctionalInstance and
 *   releaseLamaProcessor_isIdempotentAndRecreates run LAST so the class ends
 *   with empty processor caches;
 * - every test still releases what it loads in its own finally — the
 *   ordering only keeps the big nets from being co-resident, it does not
 *   replace that per-test hygiene.
 */
// the alphabetical method order below is deliberate (see the ordering
// contract in the class javadoc), do not reorder the tests for readability
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4.class)
public class MagicEraserTest {

    private static final String TAG = "MagicEraserSpikeTest";

    /**
     * Synthetic photo size so the raw 1024x1024 SAM mask (the export's
     * output plane, see EfficientSamProcessor) has to be resized to the
     * photo dimensions — the production path
     */
    private static final int IMAGE_WIDTH = 1024;
    private static final int IMAGE_HEIGHT = 768;
    /**
     * The red square to be erased, roughly 300 px, centered-ish
     */
    private static final int SQUARE_LEFT = 362;
    private static final int SQUARE_TOP = 234;
    private static final int SQUARE_SIZE = 300;
    /**
     * The display-size target of the feather+upsample sequence and of the
     * overlay bitmaps (the overlay tests resize/convert towards this
     * display-sized plane; distinct from IMAGE_WIDTH/IMAGE_HEIGHT because
     * the engine, spike and release tests need the synthetic photo size
     * instead)
     */
    private static final int DISPLAY_WIDTH = 720;
    private static final int DISPLAY_HEIGHT = 1280;

    private static MagicEraserEngine mMagicEraserEngine;
    private static MLEngineInstance mMLEngineInstance;
    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.EFFICIENT_SAM, ModelType.LAMA);
        // constructing the engine instance loads the OpenCV native library
        // before the first Mat operation (the rasterize and overlay tests
        // run before any engine inference would touch MLEngineInstance, the
        // async provider load would race it)
        mMLEngineInstance = mMLEngineProvider.get(MLEngineInstance.class);
        mMagicEraserEngine = mMLEngineProvider.get(MagicEraserEngine.class);
        // fetches nothing else: the processors stay LAZY so the memory
        // diagnostic of computeObjectMask_segmentsRedSquare measures the
        // SAM net alone (LaMa is fetched inside its own test)
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the per-test finally blocks normally released the processors;
        // this covers any test that failed before reaching them (both
        // calls are idempotent no-ops when the cache is already empty) —
        // a net left cached here would stay resident for the next class
        // in this process on the memory-constrained CI emulator
        mMLEngineInstance.releaseEfficientSamProcessor();
        mMLEngineInstance.releaseLamaProcessor();
        mMLEngineProvider.dispose();
        // the nets released above (and by the tests) are only freed when
        // their finalizers run, so we nudge gc + finalization here instead
        // of bleeding that finalizer lag into the next class' first forward
        System.gc();
        Runtime.getRuntime().runFinalization();
        // nothing of this class may pin memory for the rest of the
        // process: we null every static so the wrappers go out of reach
        // and whatever they still reference cannot keep native memory
        // alive into the next class on this shared 2GB-RAM emulator
        mMagicEraserEngine = null;
        mMLEngineInstance = null;
        mMLEngineProvider = null;
        mLogger = null;
    }

    /**
     * EfficientSAM segments the red square marked by a stroke box prompt: the
     * mask must exist, match the bitmap dimensions, cover the square center
     * and leave the far background corner unmasked. The mask is verified
     * through the production {@code toOverlayBitmap} conversion (white tint
     * ARGB bitmap at the display-only opacity) so no org.opencv pixel access
     * is needed here.
     * <p>
     * Memory spike diagnostic (test-code only): the native heap allocated is
     * printed before the SAM net is loaded (OpenCV only, both processors are
     * lazy in this test), after the first full detect (weights + the dnn
     * workspace OpenCV RETAINS inside the net after the forward), after the
     * release+gc reclaim, and after a second detect on the recreated net —
     * the recreation is the original 32-bit failure scenario (the detect
     * right after an erase could not re-create the retained workspace at the
     * 1024 input).
     */
    @Test
    public void computeObjectMask_segmentsRedSquare() {
        Bitmap bitmap = createSingleSquareImage();
        List<EditorStroke> strokes = createSquareStrokes();
        MagicEraserMask mask = null;
        Bitmap maskOverlay = null;
        try {
            spike("nativeHeapAllocated BEFORE SAM load: "
                    + Debug.getNativeHeapAllocatedSize() + " bytes");
            long startNanos = System.nanoTime();
            mask = mMagicEraserEngine.computeObjectMask(bitmap, strokes);
            long detectMillis = (System.nanoTime() - startNanos) / 1_000_000;
            spike("nativeHeapAllocated AFTER detect 1 (SAM weights + retained"
                    + " workspace, " + detectMillis + " ms incl. model parse): "
                    + Debug.getNativeHeapAllocatedSize() + " bytes");
            assertNotNull("no prompt for the square strokes", mask);
            assertTrue("mask is (nearly) empty, the square was not segmented",
                    mMagicEraserEngine.hasMask(mask));
            // production conversion path to the white tint overlay bitmap
            maskOverlay = mMagicEraserEngine.toOverlayBitmap(mask);
            // upsampled to the input bitmap resolution for compositing
            assertEquals(bitmap.getWidth(), maskOverlay.getWidth());
            assertEquals(bitmap.getHeight(), maskOverlay.getHeight());
            // roughly covering the square: the center must be masked (white
            // alpha), the far background corner must stay fully clear (the
            // mask is feathered only at the segmentation edge)
            int squareCenterAlpha = Color.alpha(maskOverlay.getPixel(
                    SQUARE_LEFT + SQUARE_SIZE / 2, SQUARE_TOP + SQUARE_SIZE / 2));
            int backgroundCornerAlpha = Color.alpha(maskOverlay.getPixel(2, 2));
            spike("mask center alpha: " + squareCenterAlpha
                    + ", background corner alpha: " + backgroundCornerAlpha);
            assertTrue("square center not masked (alpha " + squareCenterAlpha + ")",
                    squareCenterAlpha > 0);
            assertTrue("background corner masked (alpha "
                    + backgroundCornerAlpha + ")", backgroundCornerAlpha == 0);

            // release + reclaim + re-detect: the recreate-on-demand scenario
            // (release on dispose, lazy reload on the next use) that keeps
            // this 32-bit process' SAM footprint bounded between uses
            mask.release();
            mask = null;
            maskOverlay.recycle();
            maskOverlay = null;
            mMLEngineInstance.releaseEfficientSamProcessor();
            System.gc();
            System.runFinalization();
            spike("nativeHeapAllocated AFTER SAM release + gc: "
                    + Debug.getNativeHeapAllocatedSize() + " bytes");
            startNanos = System.nanoTime();
            mask = mMagicEraserEngine.computeObjectMask(bitmap, strokes);
            long redetectMillis = (System.nanoTime() - startNanos) / 1_000_000;
            spike("nativeHeapAllocated AFTER detect 2 (recreated net, "
                    + redetectMillis + " ms incl. model parse): "
                    + Debug.getNativeHeapAllocatedSize() + " bytes");
            assertTrue("recreated SAM did not re-segment the square",
                    mMagicEraserEngine.hasMask(mask));
        } finally {
            if (maskOverlay != null) {
                maskOverlay.recycle();
            }
            if (mask != null) {
                mask.release();
            }
            // leave a clean slate for the next test class on this 32-bit
            // emulator (both processors lazily reload)
            mMLEngineInstance.releaseEfficientSamProcessor();
            bitmap.recycle();
        }
    }

    /**
     * The prompt is capped at the 6 entries the model accepts: 2 box entries
     * (labels 2 = top-left, 3 = bottom-right) come first, up to 4 sampled
     * foreground points (label 1) follow, unused trailing entries are padded
     * with label -1 and coordinates 0,0. An empty stroke list converts to null.
     */
    @Test
    public void convertStrokesToPrompts_capsAtSixEntries() {
        // no strokes at all -> null
        assertNull(mMagicEraserEngine.convertStrokesToPrompts(new ArrayList<>()));

        // an empty stroke (no point) contributes nothing -> null
        ArrayList<EditorStroke> emptyStrokes = new ArrayList<>();
        emptyStrokes.add(new EditorStroke(3));
        assertNull(mMagicEraserEngine.convertStrokesToPrompts(emptyStrokes));

        // three strokes with 10 points each, the union bbox spans 0.25..0.70 x
        List<EditorStroke> strokes = new ArrayList<>();
        for (int strokeIndex = 0; strokeIndex < 3; strokeIndex++) {
            EditorStroke stroke = new EditorStroke(3);
            for (int i = 0; i < 10; i++) {
                stroke.addPoint(0.25f + i * 0.05f, 0.25f + strokeIndex * 0.25f);
            }
            strokes.add(stroke);
        }
        MagicEraserEngine.Prompts prompts =
                mMagicEraserEngine.convertStrokesToPrompts(strokes);
        assertNotNull(prompts);
        float[][] points = prompts.getPoints();
        float[] labels = prompts.getLabels();
        assertEquals(6, points.length);
        assertEquals(6, labels.length);
        // box entries first with the union bounding box mapped into the
        // model input space (the normalized points scale by the engine's
        // SAM_MODEL_INPUT_SIZE directly)
        assertEquals(MagicEraserEngine.PROMPT_LABEL_BOX_TOP_LEFT, labels[0], 0f);
        assertEquals(0.25f * MagicEraserEngine.SAM_MODEL_INPUT_SIZE, points[0][0], 0.001f);
        assertEquals(0.25f * MagicEraserEngine.SAM_MODEL_INPUT_SIZE, points[0][1], 0.001f);
        assertEquals(MagicEraserEngine.PROMPT_LABEL_BOX_BOTTOM_RIGHT, labels[1], 0f);
        assertEquals(0.70f * MagicEraserEngine.SAM_MODEL_INPUT_SIZE, points[1][0], 0.001f);
        // up to 4 sampled foreground points follow (labels 1)
        int foregroundCount = 0;
        for (int i = 2; i < 6; i++) {
            if (labels[i] == MagicEraserEngine.PROMPT_LABEL_FOREGROUND) {
                foregroundCount++;
            } else {
                // unused trailing entries are padded with label -1 and
                // coordinates 0,0
                assertEquals(-1f, labels[i], 0f);
                assertEquals(0f, points[i][0], 0f);
                assertEquals(0f, points[i][1], 0f);
            }
        }
        assertTrue("no foreground point sampled, found " + foregroundCount,
                foregroundCount > 0);
        assertTrue("prompt entries exceed the model cap",
                2 + foregroundCount <= 6);
    }

    /**
     * A single-point tap stroke (degenerate bounding box) yields a
     * points-only prompt: the tap point as foreground (label 1) at its
     * model-input-space coordinates (normalized point scaled by the engine's
     * SAM_MODEL_INPUT_SIZE), the box slots padded with label -1 and
     * coordinates 0,0
     */
    @Test
    public void convertStrokesToPrompts_singleTapYieldsPointsOnlyPrompt() {
        ArrayList<EditorStroke> strokes = new ArrayList<>();
        EditorStroke tap = new EditorStroke(3);
        tap.addPoint(0.5f, 0.25f);
        strokes.add(tap);
        MagicEraserEngine.Prompts prompts =
                mMagicEraserEngine.convertStrokesToPrompts(strokes);
        assertNotNull(prompts);
        float[][] points = prompts.getPoints();
        float[] labels = prompts.getLabels();
        assertEquals(6, points.length);
        assertEquals(6, labels.length);
        // the tap point is the foreground prompt (label 1)
        assertEquals(MagicEraserEngine.PROMPT_LABEL_FOREGROUND, labels[0], 0f);
        assertEquals(0.5f * MagicEraserEngine.SAM_MODEL_INPUT_SIZE, points[0][0], 0.001f);
        assertEquals(0.25f * MagicEraserEngine.SAM_MODEL_INPUT_SIZE, points[0][1], 0.001f);
        // the box slots stay padded with label -1 and coordinates 0,0
        for (int i = 1; i < 6; i++) {
            assertEquals(-1f, labels[i], 0f);
            assertEquals(0f, points[i][0], 0f);
            assertEquals(0f, points[i][1], 0f);
        }
    }

    /**
     * eraseRegion round-trip on a synthetic image: the red square marked by a
     * stroke is no longer red after the erase, the input bitmap is untouched
     * and the result is a new bitmap of the same dimensions.
     */
    @Test
    public void eraseRegion_removesRedSquare() {
        Bitmap bitmap = createSingleSquareImage();
        List<EditorStroke> strokes = createSquareStrokes(SQUARE_LEFT, SQUARE_TOP, SQUARE_SIZE);
        MagicEraserMask mask = null;
        Bitmap result = null;
        try {
            mask = mMagicEraserEngine.computeObjectMask(bitmap, strokes);
            assertNotNull("no mask for the square strokes", mask);
            assertTrue(mMagicEraserEngine.hasMask(mask));
            // free the SAM dnn workspace BEFORE LaMa runs: OpenCV retains a
            // large workspace inside the segmentation net after its forward,
            // on the 32-bit test emulator the co-resident nets starve the
            // LaMa forward of address space (test infrastructure only — the
            // editor page keeps SAM resident across an erase, and a next
            // detect lazily reloads SAM after any release)
            mMLEngineInstance.releaseEfficientSamProcessor();
            int center = SQUARE_LEFT + SQUARE_SIZE / 2;
            int middle = SQUARE_TOP + SQUARE_SIZE / 2;
            result = mMagicEraserEngine.eraseRegion(bitmap, mask);
            assertEquals(bitmap.getWidth(), result.getWidth());
            assertEquals(bitmap.getHeight(), result.getHeight());
            assertTrue("eraseRegion must return a new bitmap", result != bitmap);
            int originalPixel = bitmap.getPixel(center, middle);
            int resultPixel = result.getPixel(center, middle);
            assertTrue("input bitmap was modified by eraseRegion",
                    Color.red(originalPixel) > 200);
            assertTrue("square area still red after the erase (pixel "
                            + Integer.toHexString(resultPixel) + ")",
                    maxChannelDiff(resultPixel, originalPixel) > 50);
        } finally {
            if (result != null) {
                result.recycle();
            }
            if (mask != null) {
                mask.release();
            }
            // claim parity for the SAM release: the pre-erase release above
            // sits on the happy path only, so release it here as well for
            // the failure paths that never reached it (an idempotent no-op
            // when the cache is already empty), followed by the LaMa
            // release — the retained OpenCV dnn workspaces would otherwise
            // starve the next test's forwards on the 32-bit emulator (both
            // processors lazily reload)
            mMLEngineInstance.releaseEfficientSamProcessor();
            mMLEngineInstance.releaseLamaProcessor();
            bitmap.recycle();
        }
    }

    /**
     * Disjoint strokes over two separated squares: the union box of both
     * strokes spans most of the image, so the erase goes through the
     * connected-components crop path and both squares are removed.
     */
    @Test
    public void eraseRegion_removesTwoDisjointSquares() {
        // reclaim the nets the preceding erase test released first: the
        // release nudge is best effort and the finalizer deletion of the
        // big SAM native net can still be in flight when this test starts —
        // on the 32-bit emulator there is no address space to spare for
        // both, this test's SAM forward would fail with a native
        // OutOfMemoryError
        System.gc();
        System.runFinalization();
        int squareSize = 200;
        int leftSquareLeft = 102;
        int leftSquareTop = 102;
        int rightSquareLeft = 722;
        int rightSquareTop = 466;
        Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(leftSquareLeft, leftSquareTop,
                leftSquareLeft + squareSize, leftSquareTop + squareSize, paint);
        canvas.drawRect(rightSquareLeft, rightSquareTop,
                rightSquareLeft + squareSize, rightSquareTop + squareSize, paint);

        List<EditorStroke> strokes = new ArrayList<>();
        strokes.addAll(createSquareStrokes(leftSquareLeft, leftSquareTop, squareSize));
        strokes.addAll(createSquareStrokes(rightSquareLeft, rightSquareTop, squareSize));
        MagicEraserMask mask = null;
        Bitmap result = null;
        try {
            mask = mMagicEraserEngine.computeObjectMask(bitmap, strokes);
            assertNotNull("no mask for the disjoint square strokes", mask);
            assertTrue(mMagicEraserEngine.hasMask(mask));
            // free the SAM dnn workspace before LaMa runs (see
            // eraseRegion_removesRedSquare)
            mMLEngineInstance.releaseEfficientSamProcessor();
            result = mMagicEraserEngine.eraseRegion(bitmap, mask);
            // the LaMa fill of a red square on a white background is a
            // plausible near-white tone, not necessarily pure white — assert
            // the painted square centers are no longer red (same predicate as
            // the single square test)
            int leftOriginal = bitmap.getPixel(leftSquareLeft + squareSize / 2,
                    leftSquareTop + squareSize / 2);
            int rightOriginal = bitmap.getPixel(rightSquareLeft + squareSize / 2,
                    rightSquareTop + squareSize / 2);
            int leftCenter = result.getPixel(leftSquareLeft + squareSize / 2,
                    leftSquareTop + squareSize / 2);
            int rightCenter = result.getPixel(rightSquareLeft + squareSize / 2,
                    rightSquareTop + squareSize / 2);
            assertTrue("left square area still red after the erase (pixel "
                            + Integer.toHexString(leftCenter) + ")",
                    maxChannelDiff(leftCenter, leftOriginal) > 50);
            assertTrue("right square area still red after the erase (pixel "
                            + Integer.toHexString(rightCenter) + ")",
                    maxChannelDiff(rightCenter, rightOriginal) > 50);
        } finally {
            if (result != null) {
                result.recycle();
            }
            if (mask != null) {
                mask.release();
            }
            // claim parity for the SAM release (see
            // eraseRegion_removesRedSquare), then the LaMa release
            mMLEngineInstance.releaseEfficientSamProcessor();
            mMLEngineInstance.releaseLamaProcessor();
            bitmap.recycle();
        }
    }

    /**
     * The engine's in-place feather + upsample sequence on a synthetic raw
     * mask at the size the export actually emits (the fixed 1024x1024
     * output plane — a smaller input leaves it unchanged, measured on
     * device, see
     * EfficientSamProcessor; ~1.5% positives, matching a real SAM raw
     * mask): the engine's feather kernel (3% of the actual plane side via
     * samMaskFeatherKernel) and the in-place resize
     * to the display dimensions must keep the mask a small fraction of the
     * image with an approximately preserved mean.
     */
    @Test
    public void inPlaceBlurAndResizePreserveSmallMask() {
        // the export's fixed output plane side (a smaller input leaves it
        // unchanged, measured on device)
        int maskDims = 1024;
        int rectSize = maskDims / 8;
        int featherKernel = MagicEraserEngine.samMaskFeatherKernel(maskDims);
        Mat pipeline = syntheticRectMask(maskDims, maskDims, rectSize, rectSize);
        try {
            long totalMask = (long) pipeline.cols() * pipeline.rows();
            long before = Core.countNonZero(pipeline);
            double meanBefore = Core.mean(pipeline).val[0];
            Imgproc.GaussianBlur(pipeline, pipeline, new Size(featherKernel, featherKernel), 0);
            long afterBlur = Core.countNonZero(pipeline);
            double meanAfterBlur = Core.mean(pipeline).val[0];
            Imgproc.resize(pipeline, pipeline,
                    new Size(DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    0, 0, Imgproc.INTER_LINEAR);
            long totalBitmap = (long) pipeline.cols() * pipeline.rows();
            long afterResize = Core.countNonZero(pipeline);
            double meanAfterResize = Core.mean(pipeline).val[0];
            System.out.println("OVERLAY_TEST: inPlace before=" + before
                    + "/" + totalMask + " (" + fraction(before, totalMask)
                    + "), afterBlur=" + afterBlur + " ("
                    + fraction(afterBlur, totalMask) + "), afterResize="
                    + afterResize + "/" + totalBitmap + " ("
                    + fraction(afterResize, totalBitmap) + "), mean "
                    + meanBefore + " -> " + meanAfterBlur + " -> "
                    + meanAfterResize);
            assertTrue("blur must not lose positives (before=" + before
                            + ", afterBlur=" + afterBlur + ")",
                    afterBlur >= before);
            assertTrue("blurred mask must stay a small fraction of the image",
                    afterBlur < totalMask / 2);
            assertTrue("resize must keep positives", afterResize > 0);
            assertTrue("resized mask must stay a small fraction of the image ("
                            + afterResize + " of " + totalBitmap + ")",
                    afterResize < totalBitmap / 2);
            // the feather kernel is normalized and the rect sits far from
            // the borders, the mean is preserved almost exactly
            assertEquals("blur must preserve the mean", meanBefore,
                    meanAfterBlur, meanBefore * 0.05);
            // the interpolating resize is only approximately energy keeping
            assertEquals("resize must preserve the mean", meanAfterBlur,
                    meanAfterResize, meanAfterBlur * 0.25);
        } finally {
            pipeline.release();
        }
    }

    /**
     * LaMa inpaints the red square region: the output must match the crop
     * dimensions and must not be all-zero. Whether the fill looks plausible is
     * a human judgement from the logged timing, not an assertion.
     */
    @Test
    public void inpaint_fillsSquareRegion() {
        // LaMa fetched lazily here (not in beforeAnyTest) so the SAM memory
        // diagnostic of computeObjectMask_segmentsRedSquare runs without the
        // LaMa weights resident
        LamaProcessor lamaProcessor = mMLEngineInstance.getLamaProcessor();
        Bitmap bitmap = createSingleSquareImage();
        // crop with 50% context padding around the square (the engine crop
        // strategy), clamped into the image
        int expandX = SQUARE_SIZE / 2;
        int expandY = SQUARE_SIZE / 2;
        int left = Math.max(0, SQUARE_LEFT - expandX);
        int top = Math.max(0, SQUARE_TOP - expandY);
        int right = Math.min(IMAGE_WIDTH, SQUARE_LEFT + SQUARE_SIZE + expandX);
        int bottom = Math.min(IMAGE_HEIGHT, SQUARE_TOP + SQUARE_SIZE + expandY);
        int cropWidth = right - left;
        int cropHeight = bottom - top;

        Mat rgba = null;
        Mat region = null;
        Mat bgrRegion = null;
        Mat grayMask = null;
        Mat inpainted = null;
        try {
            rgba = new Mat();
            Utils.bitmapToMat(bitmap, rgba);
            region = new Mat(rgba, new org.opencv.core.Rect(left, top,
                    cropWidth, cropHeight));
            bgrRegion = new Mat();
            Imgproc.cvtColor(region, bgrRegion, Imgproc.COLOR_RGBA2BGR);
            grayMask = new Mat(cropHeight, cropWidth, CvType.CV_8U, new Scalar(0));
            Imgproc.rectangle(grayMask,
                    new org.opencv.core.Point(SQUARE_LEFT - left, SQUARE_TOP - top),
                    new org.opencv.core.Point(SQUARE_LEFT - left + SQUARE_SIZE,
                            SQUARE_TOP - top + SQUARE_SIZE),
                    new Scalar(255), -1);

            long startNanos = System.nanoTime();
            inpainted = lamaProcessor.inpaint(bgrRegion, grayMask);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            spike("LaMa inpaint took " + elapsedMillis + " ms");

            assertEquals(cropWidth, inpainted.cols());
            assertEquals(cropHeight, inpainted.rows());
            assertEquals(3, inpainted.channels());
            double sum = Core.sumElems(inpainted).val[0]
                    + Core.sumElems(inpainted).val[1]
                    + Core.sumElems(inpainted).val[2];
            spike("inpainted output pixel sum: " + Math.round(sum));
            assertTrue("inpainted output is all-zero", sum > 0);
        } finally {
            if (inpainted != null) {
                inpainted.release();
            }
            if (grayMask != null) {
                grayMask.release();
            }
            if (bgrRegion != null) {
                bgrRegion.release();
            }
            if (region != null) {
                region.release();
            }
            if (rgba != null) {
                rgba.release();
            }
            bitmap.recycle();
            // the LaMa net must not stay cached for the remainder of the
            // run: the processor wrapper is a local here, but the engine
            // instance still caches the net itself, and the following test
            // classes (PersonSeg/QR/NST) share this process on a
            // memory-constrained CI emulator — we release the net here
            // exactly like the erase tests do, so the cached processor
            // lets go of the OpenCV Net (and its retained dnn workspace)
            // now instead of pinning it for the rest of the run
            mMLEngineInstance.releaseLamaProcessor();
        }
    }

    /**
     * The no-object contract at the engine boundary: an all-zero mask
     * (at the size the export actually emits — the fixed 1024x1024 output
     * plane, see EfficientSamProcessor — matching a real SAM raw mask where
     * nothing was segmented) must be reported as "no object" by hasMask, and
     * its overlay conversion must stay fully transparent (every sampled
     * pixel alpha 0), so a no-object detect can never flash an overlay over
     * the image.
     */
    @Test
    public void noObjectContract() {
        int maskDims = 1024;
        Mat mat = new Mat(maskDims, maskDims, CvType.CV_8U, Scalar.all(0));
        MagicEraserMask mask = null;
        Bitmap overlay = null;
        try {
            mask = new MagicEraserMask(mat);
            assertFalse("an all-zero mask must report no object",
                    mMagicEraserEngine.hasMask(mask));
            overlay = mMagicEraserEngine.toOverlayBitmap(mask);
            int width = overlay.getWidth();
            int height = overlay.getHeight();
            assertEquals("overlay width must match the mask", maskDims, width);
            assertEquals("overlay height must match the mask", maskDims, height);
            long sampled = 0;
            for (int y = 0; y < height; y += 16) {
                for (int x = 0; x < width; x += 16) {
                    assertEquals("overlay pixel must be fully transparent at ("
                                    + x + "," + y + ")", 0,
                            Color.alpha(overlay.getPixel(x, y)));
                    sampled++;
                }
            }
            System.out.println("OVERLAY_TEST: noObjectContract sampled="
                    + sampled + " pixels, every sampled alpha is 0"
                    + ", hasMask=false");
        } finally {
            if (overlay != null) {
                overlay.recycle();
            }
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * The overlay of a synthetic mask must be VALID premultiplied data at
     * the display tint: for every pixel the stored R,G,B must not exceed the
     * stored A (a corner stores nothing, the rect center stores white at the
     * OVERLAY_TINT_ALPHA_SCALE opacity), and the unpremultiplied getPixel
     * view must agree (corner 0x00000000, center white at ~half opacity,
     * i.e. RGB all set under an alpha of 255 * OVERLAY_TINT_ALPHA_SCALE).
     */
    @Test
    public void overlayConversion_isPremultipliedValid() {
        Mat mat = syntheticRectMask(DISPLAY_WIDTH, DISPLAY_HEIGHT, 200, 300);
        MagicEraserMask mask = null;
        Bitmap overlay = null;
        try {
            mask = new MagicEraserMask(mat);
            overlay = mMagicEraserEngine.toOverlayBitmap(mask);
            // raw stored bytes: an ARGB_8888 buffer holds RGBA byte quads in
            // premultiplied form (getPixel would unpremultiply and hide a
            // violated invariant, the all-white overlay proved they diverge)
            ByteBuffer buffer = ByteBuffer.allocate(overlay.getByteCount());
            overlay.copyPixelsToBuffer(buffer);
            int width = overlay.getWidth();
            int height = overlay.getHeight();
            assertEquals("buffer must hold width*height*4 bytes",
                    (long) width * height * 4, buffer.capacity());
            long sampled = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int base = (y * width + x) * 4;
                    int r = buffer.get(base) & 0xFF;
                    int g = buffer.get(base + 1) & 0xFF;
                    int b = buffer.get(base + 2) & 0xFF;
                    int a = buffer.get(base + 3) & 0xFF;
                    assertTrue("premultiplied invariant violated at ("
                                    + x + "," + y + "): rgba=(" + r + "," + g
                                    + "," + b + "," + a + ")",
                            r <= a && g <= a && b <= a);
                    sampled++;
                }
            }
            // corner (mask 0): nothing stored, fully transparent in both views
            assertEquals("corner stored alpha must be 0", 0,
                    buffer.get((5 * width + 5) * 4 + 3) & 0xFF);
            assertEquals("corner must be fully transparent", 0x00000000,
                    overlay.getPixel(5, 5));
            // center of the filled rect (mask 255): white tinted at the
            // display opacity in both views, the stored alpha sits at
            // 255 * 0.5 (rounding allowed)
            int centerBase = ((height / 2) * width + (width / 2)) * 4;
            int centerAlpha = buffer.get(centerBase + 3) & 0xFF;
            assertTrue("rect center stored alpha must be the 0.5 tint of 255, got "
                            + centerAlpha,
                    centerAlpha >= 100 && centerAlpha <= 160);
            assertEquals("rect center stored red must match its alpha",
                    centerAlpha, buffer.get(centerBase) & 0xFF);
            assertEquals("rect center must be white at ~half opacity",
                    0x00FFFFFF, overlay.getPixel(
                            DISPLAY_WIDTH / 2, DISPLAY_HEIGHT / 2) & 0x00FFFFFF);
            assertNotEquals("rect center must not be fully transparent",
                    0x00000000,
                    overlay.getPixel(DISPLAY_WIDTH / 2, DISPLAY_HEIGHT / 2));
            System.out.println("OVERLAY_TEST: overlayConversion sampled="
                    + sampled + " pixels, premultiplied invariant holds"
                    + ", corner=0x"
                    + Integer.toHexString(overlay.getPixel(5, 5))
                    + ", center=0x" + Integer.toHexString(
                    overlay.getPixel(DISPLAY_WIDTH / 2, DISPLAY_HEIGHT / 2)));
        } finally {
            if (overlay != null) {
                overlay.recycle();
            }
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * rasterizeMask draws the strokes as thick anti-aliased segments: the
     * stroke path pixels must be masked and a far away pixel must stay
     * background.
     */
    @Test
    public void rasterizeMask_coversStrokeArea() {
        List<EditorStroke> strokes = new ArrayList<>();
        EditorStroke stroke = new EditorStroke(3);
        stroke.addPoint(0.25f, 0.5f);
        stroke.addPoint(0.75f, 0.5f);
        strokes.add(stroke);
        Mat mask = null;
        try {
            mask = mMagicEraserEngine.rasterizeMask(strokes, IMAGE_WIDTH, IMAGE_HEIGHT);
            assertEquals(IMAGE_WIDTH, mask.cols());
            assertEquals(IMAGE_HEIGHT, mask.rows());
            assertEquals(1, mask.channels());
            assertEquals(CvType.CV_8U, mask.depth());
            // the stroke path is masked (including the segment middle)
            assertTrue("stroke start point not masked",
                    maskValue(mask, IMAGE_WIDTH / 4, IMAGE_HEIGHT / 2) == 255);
            assertTrue("stroke middle not masked",
                    maskValue(mask, IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2) == 255);
            assertTrue("stroke end point not masked",
                    maskValue(mask, 3 * IMAGE_WIDTH / 4, IMAGE_HEIGHT / 2) == 255);
            // a pixel far away from the stroke stays background
            assertEquals(0, maskValue(mask, IMAGE_WIDTH / 20, IMAGE_HEIGHT / 10));
        } finally {
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * Release the cached EfficientSAM processor and get it again: the next
     * accessor must lazily recreate a fresh functional instance (the
     * production engine path segments the marked square with it) and a
     * released processor must refuse further inference.
     */
    @Test
    public void releaseEfficientSamProcessor_thenRegetYieldsFunctionalInstance() {
        // release and verify the dead instance refuses inference — the
        // released wrapper must not stay reachable in this test afterwards
        // (the native net is reclaimed through its finalizer, holding it
        // would keep the old net's memory alive into the recreation)
        releaseAndVerifySamRefusal();

        // the recreation is functional through the production engine path,
        // which fetches its processor lazily (exactly the recreate-on-demand
        // path of MLEngineInstance)
        List<EditorStroke> strokes = createSquareStrokes();
        Bitmap bitmap = createSingleSquareImage();
        MagicEraserMask mask = null;
        try {
            mask = mMagicEraserEngine.computeObjectMask(bitmap, strokes);
            assertNotNull("no mask after the processor recreation", mask);
            assertTrue("the recreated processor computed an empty mask",
                    mMagicEraserEngine.hasMask(mask));
        } finally {
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }

        // releasing twice (the second release hits the empty cache) is a
        // safe no-op
        mMLEngineInstance.releaseEfficientSamProcessor();
        mMLEngineInstance.releaseEfficientSamProcessor();
    }

    /**
     * The LaMa release path: the engine remove-and-release on an already
     * directly released processor must be a safe no-op (idempotency is what
     * makes the remove-then-release sequence safe), and the next accessor
     * must hand out a fresh instance that is functionally verified through
     * a full inpaint round-trip (an identity check alone would accept a
     * broken recreation).
     */
    @Test
    public void releaseLamaProcessor_isIdempotentAndRecreates() {
        // reclaim the nets the preceding test released first: the release
        // nudge is best effort and the finalizer deletion of the big SAM
        // native net can still be in flight when this test starts — on the
        // 32-bit emulator there is no address space to spare for both, the
        // re-parse below would fail with a native OutOfMemoryError
        System.gc();
        System.runFinalization();

        // the identity checks run in a helper scope: the released and the
        // replaced wrappers must not stay live references of this test
        // (their native nets are reclaimed through the finalizer, holding
        // them would keep the old nets' memory — forward workspace
        // included — alive into the round-trip below and starve it on the
        // 32-bit emulator)
        releaseAndRecreateLamaIdempotent();

        // functional inpaint round-trip on the fresh instance through the
        // production engine path (eraseRegion fetches its processor lazily,
        // exactly the recreate-on-demand path of MLEngineInstance), same
        // input pattern as the engine erase tests. The mask is built
        // directly from the square rect instead of computeObjectMask: the
        // round-trip must exercise only LaMa (a SAM forward here would
        // retain its workspace in this 32-bit test process for no extra
        // LaMa coverage)
        Bitmap bitmap = createSingleSquareImage();
        MagicEraserMask mask = null;
        Bitmap result = null;
        Mat maskMat = null;
        try {
            maskMat = new Mat(IMAGE_HEIGHT, IMAGE_WIDTH,
                    CvType.CV_8U, new Scalar(0));
            Imgproc.rectangle(maskMat, new Point(SQUARE_LEFT, SQUARE_TOP),
                    new Point(SQUARE_LEFT + SQUARE_SIZE,
                            SQUARE_TOP + SQUARE_SIZE),
                    new Scalar(255), -1);
            mask = new MagicEraserMask(maskMat);
            result = mMagicEraserEngine.eraseRegion(bitmap, mask);
            assertEquals(IMAGE_WIDTH, result.getWidth());
            assertEquals(IMAGE_HEIGHT, result.getHeight());
            int center = SQUARE_LEFT + SQUARE_SIZE / 2;
            int middle = SQUARE_TOP + SQUARE_SIZE / 2;
            int originalPixel = bitmap.getPixel(center, middle);
            int resultPixel = result.getPixel(center, middle);
            assertTrue("input bitmap was modified by eraseRegion",
                    Color.red(originalPixel) > 200);
            assertTrue("square area still red after the erase on the "
                            + "recreated processor (pixel "
                            + Integer.toHexString(resultPixel) + ")",
                    maxChannelDiff(resultPixel, originalPixel) > 50);
        } finally {
            if (mask != null) {
                mask.release();
            }
            // idempotent, MagicEraserMask.release() already released the same data
            if (maskMat != null) {
                maskMat.release();
            }
            if (result != null) {
                result.recycle();
            }
            // release the LaMa net the round-trip just used (the page's
            // contract after every erase): its retained workspace would
            // otherwise starve a following test's forward on the 32-bit
            // emulator (the nudge inside the release also finalizes the
            // wrappers stranded by the helper above)
            mMLEngineInstance.releaseLamaProcessor();
            bitmap.recycle();
        }
    }

    /**
     * Get the cached processor, release it through the engine instance and
     * verify the released instance refuses further inference (fail fast
     * instead of touching the freed native net). The helper scope keeps the
     * released wrapper out of the calling test's live references.
     */
    private void releaseAndVerifySamRefusal() {
        EfficientSamProcessor processor = mMLEngineInstance.getEfficientSamProcessor();
        assertNotNull(processor);
        mMLEngineInstance.releaseEfficientSamProcessor();
        try {
            List<EditorStroke> strokes = createSquareStrokes();
            MagicEraserEngine.Prompts prompts =
                    mMagicEraserEngine.convertStrokesToPrompts(strokes);
            assertNotNull(prompts);
            Bitmap bitmap = createSingleSquareImage();
            try {
                processor.computeRawMask(bitmap, prompts.getPoints(),
                        prompts.getLabels(), "releaseTest: ");
                fail("a released processor must refuse inference");
            } finally {
                bitmap.recycle();
            }
        } catch (IllegalStateException expected) {
            // the documented released-processor contract
        }
    }

    /**
     * The remove-and-release idempotency check of the LaMa path, scoped so
     * the handled processor wrappers go out of reach when it returns (see
     * the calling test).
     */
    private void releaseAndRecreateLamaIdempotent() {
        LamaProcessor first = mMLEngineInstance.getLamaProcessor();
        assertNotNull(first);
        // direct release, then the engine release of the same (now released)
        // instance removed from the cache: the second release must no-op
        first.release();
        mMLEngineInstance.releaseLamaProcessor();
        LamaProcessor second = mMLEngineInstance.getLamaProcessor();
        assertNotNull(second);
        assertNotEquals("the released processor must not be handed out again",
                first, second);
    }

    /**
     * White background with one solid red square at the given position
     */
    private static Bitmap createSingleSquareImage() {
        Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(SQUARE_LEFT, SQUARE_TOP,
                SQUARE_LEFT + SQUARE_SIZE, SQUARE_TOP + SQUARE_SIZE, paint);
        return bitmap;
    }

    /**
     * A stroke tracing a square perimeter at the given top-left position
     * (in pixels) with the given size, so the union bounding box prompt
     * covers that painted square exactly (all sampled foreground points sit
     * on the painted square)
     */
    private static List<EditorStroke> createSquareStrokes(int left, int top, int size) {
        List<EditorStroke> strokes = new ArrayList<>();
        EditorStroke stroke = new EditorStroke(3);
        stroke.addPoint((float) left / IMAGE_WIDTH, (float) top / IMAGE_HEIGHT);
        stroke.addPoint((float) (left + size) / IMAGE_WIDTH,
                (float) top / IMAGE_HEIGHT);
        stroke.addPoint((float) (left + size) / IMAGE_WIDTH,
                (float) (top + size) / IMAGE_HEIGHT);
        stroke.addPoint((float) left / IMAGE_WIDTH,
                (float) (top + size) / IMAGE_HEIGHT);
        stroke.addPoint((float) left / IMAGE_WIDTH, (float) top / IMAGE_HEIGHT);
        strokes.add(stroke);
        return strokes;
    }

    /**
     * A stroke tracing the red square perimeter so the union bounding box
     * prompt covers the square
     */
    private static List<EditorStroke> createSquareStrokes() {
        List<EditorStroke> strokes = new ArrayList<>();
        EditorStroke stroke = new EditorStroke(3);
        stroke.addPoint((float) SQUARE_LEFT / IMAGE_WIDTH,
                (float) SQUARE_TOP / IMAGE_HEIGHT);
        stroke.addPoint((float) (SQUARE_LEFT + SQUARE_SIZE) / IMAGE_WIDTH,
                (float) SQUARE_TOP / IMAGE_HEIGHT);
        stroke.addPoint((float) (SQUARE_LEFT + SQUARE_SIZE) / IMAGE_WIDTH,
                (float) (SQUARE_TOP + SQUARE_SIZE) / IMAGE_HEIGHT);
        stroke.addPoint((float) SQUARE_LEFT / IMAGE_WIDTH,
                (float) (SQUARE_TOP + SQUARE_SIZE) / IMAGE_HEIGHT);
        stroke.addPoint((float) SQUARE_LEFT / IMAGE_WIDTH,
                (float) SQUARE_TOP / IMAGE_HEIGHT);
        strokes.add(stroke);
        return strokes;
    }

    private static int maskValue(Mat mask, int x, int y) {
        byte[] pixel = new byte[1];
        mask.get(y, x, pixel);
        return pixel[0] & 0xFF;
    }

    /**
     * Max per channel color difference of two pixels.
     */
    private static int maxChannelDiff(int expected, int actual) {
        return Math.max(
                Math.abs(Color.red(expected) - Color.red(actual)),
                Math.max(
                        Math.abs(Color.green(expected) - Color.green(actual)),
                        Math.abs(Color.blue(expected) - Color.blue(actual))));
    }

    /**
     * A width x height CV_8U mask, all zeros, with a centered rectangle of
     * the given size filled 255
     */
    private static Mat syntheticRectMask(int width, int height,
                                         int rectWidth, int rectHeight) {
        Mat mask = new Mat(height, width, CvType.CV_8U, new Scalar(0));
        int left = (width - rectWidth) / 2;
        int top = (height - rectHeight) / 2;
        try {
            Imgproc.rectangle(mask,
                    new Point(left, top),
                    new Point(left + rectWidth, top + rectHeight),
                    new Scalar(255), -1);
        } catch (RuntimeException e) {
            mask.release();
            throw e;
        }
        return mask;
    }

    private static String fraction(long part, long total) {
        if (total == 0) {
            return "n/a";
        }
        return String.format(Locale.US, "%.4f", (double) part / total);
    }

    /**
     * The spike log marker, easy to grep from the instrumented test output
     */
    private static void spike(String message) {
        String line = "SPIKE: " + message;
        mLogger.i(TAG, line);
        System.out.println(line);
    }
}
