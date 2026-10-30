package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of the processor release contract of
 * {@link MLEngineInstance}, which will execute on an Android device (models
 * auto-downloaded via {@link ModelTestHelper}): a released processor must be
 * removed from the cache so the next accessor lazily recreates a fresh
 * functional instance (the OpenCV dnn workspace retained inside the native
 * net must not pin the released processor in place), a released processor
 * must refuse further inference and releasing must be idempotent.
 */
// fixed method order for the 32-bit test emulator: the SAM recreation
// forward needs one large contiguous native allocation, running it after
// the LaMa round-trip's allocation churn fragments the address space and
// fails the forward with a native OutOfMemoryError even though both tests
// reclaim their nets — alphabetical order runs the SAM test first
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4.class)
public class MagicEraserProcessorReleaseTest {

    private static final int IMAGE_WIDTH = 1024;
    private static final int IMAGE_HEIGHT = 768;
    private static final int SQUARE_LEFT = 362;
    private static final int SQUARE_TOP = 234;
    private static final int SQUARE_SIZE = 300;

    private static MagicEraserEngine mMagicEraserEngine;
    private static MLEngineInstance mMLEngineInstance;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.EFFICIENT_SAM, ModelType.LAMA);
        mMagicEraserEngine = mMLEngineProvider.get(MagicEraserEngine.class);
        mMLEngineInstance = mMLEngineProvider.get(MLEngineInstance.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
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
        try {
            Mat maskMat = new Mat(IMAGE_HEIGHT, IMAGE_WIDTH,
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
     * White background with one solid red square, the object to be marked
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
     * A stroke tracing the red square perimeter so the prompt covers the
     * painted square
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
