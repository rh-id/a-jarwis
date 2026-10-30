package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented spike test of the magic eraser models, which will execute on an
 * Android device (models auto-downloaded via {@link ModelTestHelper}). It is a
 * load-and-run spike: the assertions are functional only (the models load, run
 * and return the expected shapes/content), the latency printed in the SPIKE
 * log lines is judged by a human from the logs, never by a threshold.
 */
@RunWith(AndroidJUnit4.class)
// fixed order so computeObjectMask_segmentsRedSquare (the SAM memory
// diagnostic) always runs before inpaint_fillsSquareRegion: the LaMa net
// must not be resident when the before-SAM heap snapshot is taken
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MagicEraserSpikeTest {

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

    private static MagicEraserEngine mMagicEraserEngine;
    private static LamaProcessor mLamaProcessor;
    private static MLEngineInstance mMLEngineInstance;
    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.EFFICIENT_SAM, ModelType.LAMA);
        mMagicEraserEngine = mMLEngineProvider.get(MagicEraserEngine.class);
        // loads the OpenCV native library; the processors stay LAZY so the
        // memory diagnostic of computeObjectMask_segmentsRedSquare measures
        // the SAM net alone (LaMa is fetched inside its own test)
        mMLEngineInstance = mMLEngineProvider.get(MLEngineInstance.class);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
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
        Bitmap bitmap = createSyntheticImage();
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
     * LaMa inpaints the red square region: the output must match the crop
     * dimensions and must not be all-zero. Whether the fill looks plausible is
     * a human judgement from the logged timing, not an assertion.
     */
    @Test
    public void inpaint_fillsSquareRegion() {
        // LaMa fetched lazily here (not in beforeAnyTest) so the SAM memory
        // diagnostic of computeObjectMask_segmentsRedSquare runs without the
        // LaMa weights resident
        mLamaProcessor = mMLEngineInstance.getLamaProcessor();
        Bitmap bitmap = createSyntheticImage();
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
            inpainted = mLamaProcessor.inpaint(bgrRegion, grayMask);
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
        }
    }

    /**
     * White background with one solid red square, the object to be erased
     */
    private static Bitmap createSyntheticImage() {
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

    /**
     * The spike log marker, easy to grep from the instrumented test output
     */
    private static void spike(String message) {
        String line = "SPIKE: " + message;
        mLogger.i(TAG, line);
        System.out.println(line);
    }
}
