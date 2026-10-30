package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link MagicEraserEngine}, which will execute on an
 * Android device (models auto-downloaded via {@link ModelTestHelper}),
 * verifying the stroke rasterization, the prompt conversion and the erase
 * round-trip on synthetic images.
 */
@RunWith(AndroidJUnit4.class)
public class MagicEraserEngineTest {

    private static final int IMAGE_WIDTH = 1024;
    private static final int IMAGE_HEIGHT = 768;
    private static final int SQUARE_SIZE = 300;
    /**
     * Top-left position of the red square in the single square image
     */
    private static final int SQUARE_LEFT = 362;
    private static final int SQUARE_TOP = 234;

    private static MagicEraserEngine mMagicEraserEngine;
    private static MLEngineInstance mMLEngineInstance;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.EFFICIENT_SAM, ModelType.LAMA);
        // constructing the engine instance loads the OpenCV native library
        // before the first Mat operation (same as MagicEraserOverlayTest:
        // the rasterize test runs before any engine inference would touch
        // MLEngineInstance, the async provider load would race it)
        mMLEngineInstance = mMLEngineProvider.get(MLEngineInstance.class);
        mMagicEraserEngine = mMLEngineProvider.get(MagicEraserEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
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
}
