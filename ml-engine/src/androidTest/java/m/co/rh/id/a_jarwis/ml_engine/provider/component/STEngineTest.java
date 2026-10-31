package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class STEngineTest {

    private static STEngine mSTEngine;
    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.NST_MOSAIC, ModelType.NST_CANDY,
                ModelType.NST_RAIN_PRINCESS, ModelType.NST_UDNIE,
                ModelType.NST_POINTILISM);
        mSTEngine = mMLEngineProvider.get(STEngine.class);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
    }

    @Test
    public void applyMosaic() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        Bitmap bitmap1 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.amber)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            bitmap1 = mSTEngine.applyMosaic(bitmap);
            File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyMosaic.jpg");
            // Bitmap.compress does not close its stream
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } finally {
            // the input and result bitmaps are native-backed, a CI emulator
            // lmk-killed this process once — nothing native may outlive the
            // test on any path
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (bitmap1 != null) {
                bitmap1.recycle();
            }
        }
    }

    @Test
    public void applyCandy() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        Bitmap bitmap1 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.amber)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            bitmap1 = mSTEngine.applyCandy(bitmap);
            File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyCandy.jpg");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (bitmap1 != null) {
                bitmap1.recycle();
            }
        }
    }

    @Test
    public void applyRainPrincess() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        Bitmap bitmap1 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.amber)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            bitmap1 = mSTEngine.applyRainPrincess(bitmap);
            File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyRainPrincess.jpg");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (bitmap1 != null) {
                bitmap1.recycle();
            }
        }
    }

    @Test
    public void applyUdnie() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        Bitmap bitmap1 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.amber)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            bitmap1 = mSTEngine.applyUdnie(bitmap);
            File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyUdnie.jpg");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (bitmap1 != null) {
                bitmap1.recycle();
            }
        }
    }

    @Test
    public void applyPointilism() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        Bitmap bitmap1 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.amber)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            bitmap1 = mSTEngine.applyPointilism(bitmap);
            File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyPointilism.jpg");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (bitmap1 != null) {
                bitmap1.recycle();
            }
        }
    }

    /**
     * The release contract of a directly constructed {@link STProcessor}: it
     * must return a usable result, must refuse further inference once
     * released (fail fast instead of touching the freed native net) and
     * releasing more than once must be a safe no-op. The processor is
     * constructed directly (never through the engine instance) so the
     * cached instances of {@link #mSTEngine} are never touched and the
     * released instance is discarded here.
     */
    @Test
    public void stProcessor_releaseContract() {
        Context context = mMLEngineProvider.getContext();
        STProcessor processor = new STProcessor(
                ModelCatalog.getModelFile(context, ModelType.NST_MOSAIC)
                        .getAbsolutePath(), mLogger);
        Bitmap input = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        input.eraseColor(Color.RED);
        try {
            Bitmap result = processor.process(input);
            try {
                assertNotNull(result);
            } finally {
                result.recycle();
            }
            processor.release();
            try {
                processor.process(input);
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
            // tests of MagicEraserTest)
            System.gc();
            Runtime.getRuntime().runFinalization();
        }
    }
}