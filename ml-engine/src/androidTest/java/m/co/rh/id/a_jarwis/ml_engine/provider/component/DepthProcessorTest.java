package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.io.InputStream;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link DepthProcessor}, which will execute on an
 * Android device (model auto-downloaded via {@link ModelTestHelper}),
 * verifying the closeness map computation (dimensions, value range, flat
 * guard). The release contract test lives in {@link DepthEngineTest},
 * mirroring the PersonSegEngineTest structure.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class DepthProcessorTest {

    private static final String TAG = "DepthProcessorTest";

    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider, ModelType.DEPTH_ESTIMATION);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // release the engine-cached depth processor and nudge the runtime
        // BEFORE the provider dispose: the ~480-550 MB native dnn workspace
        // must not be inherited by the heavy suites that run later in the
        // same instrumentation process — the first connected run lost the
        // Swin2SR suite to the lowmemorykiller when the depth workspace
        // stacked on its tiled peak (a plain dispose cascade release +
        // single nudge proved not enough, the explicit gc/runFinalization
        // prelude is the FaceEngineTest release precedent). The processor
        // was cached by the value tests through the engine instance, the
        // package-private release reaches it the same way.
        mMLEngineProvider.get(MLEngineInstance.class).releaseDepthProcessor();
        System.gc();
        Runtime.getRuntime().runFinalization();
        mMLEngineProvider.dispose();
    }

    /**
     * The closeness map is upsampled back to the input bitmap resolution, not
     * delivered at the model grid resolution: same dimensions as the input,
     * a single CV_32F channel with every pixel in the normalized 0..1 range.
     * The landscape photo has real depth separation (verified by the
     * issue-#6 spike), so the map must not be flat (a flat map would mean
     * the flat guard swallowed the whole signal).
     */
    @Test
    public void computeDepth_matchesInputDimensionsAndRange() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.landscape);
        DepthProcessor processor = cachedProcessor();
        Mat depth = processor.computeDepth(bitmap);
        try {
            assertEquals(bitmap.getWidth(), depth.cols());
            assertEquals(bitmap.getHeight(), depth.rows());
            assertEquals(1, depth.channels());
            assertEquals(CvType.CV_32F, depth.depth());
            // scan every pixel (CV_32F single channel bulk row read; the map
            // is a 2-D Mat, the documented OpenCV 5 zero-read trap only
            // affects n-dimensional blobs)
            int cols = depth.cols();
            int rows = depth.rows();
            float[] row = new float[cols];
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int y = 0; y < rows; y++) {
                depth.get(y, 0, row);
                for (int x = 0; x < cols; x++) {
                    float value = row[x];
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            assertTrue("closeness min pixel " + min + " below 0", min >= 0f);
            assertTrue("closeness max pixel " + max + " above 1", max <= 1f);
            assertTrue("closeness map is flat (max " + max + " min " + min
                            + "), the real depth separation got lost",
                    max - min > 1e-3f);
        } finally {
            depth.release();
            bitmap.recycle();
        }
    }

    /**
     * A solid-color bitmap carries (near-)no depth signal: the call must not
     * crash and the map must stay in the normalized 0..1 range. When the raw
     * model output is flat (max-min &lt; 1e-6, the flat guard's trigger) the
     * guard must have written the fully-closeness 1.0 everywhere instead of
     * dividing by ~0 — that implication is asserted on the output (the
     * engine's weight math turns a uniform-1.0 map into a no-op blur). The
     * actual span is logged for the run report either way.
     */
    @Test
    public void computeDepth_flatInputHitsFlatGuard() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        DepthProcessor processor = cachedProcessor();
        Mat depth = processor.computeDepth(bitmap);
        try {
            assertEquals(bitmap.getWidth(), depth.cols());
            assertEquals(bitmap.getHeight(), depth.rows());
            int cols = depth.cols();
            int rows = depth.rows();
            float[] row = new float[cols];
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int y = 0; y < rows; y++) {
                depth.get(y, 0, row);
                for (int x = 0; x < cols; x++) {
                    float value = row[x];
                    assertTrue("closeness at (" + x + "," + y + ") is " + value
                                    + ", outside the normalized 0..1 range",
                            value >= 0f && value <= 1f);
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            mLogger.d(TAG, "computeDepth_flatInput span=" + (max - min));
            if (max - min < 1e-6f) {
                // the flat guard hit: the map must be the uniform
                // fully-closeness 1.0 (a re-scan, the values are cached in
                // the row buffer of the last pass otherwise)
                for (int y = 0; y < rows; y++) {
                    depth.get(y, 0, row);
                    for (int x = 0; x < cols; x++) {
                        assertEquals("flat guard did not write the uniform"
                                        + " fully-closeness 1.0 at (" + x + "," + y + ")",
                                1.0f, row[x], 1e-6f);
                    }
                }
            }
        } finally {
            depth.release();
            bitmap.recycle();
        }
    }

    /**
     * The processor cached inside the engine instance (never released here,
     * the provider dispose drains it, see the AfterClass note)
     */
    private static DepthProcessor cachedProcessor() {
        return mMLEngineProvider.get(MLEngineInstance.class).getDepthProcessor();
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
