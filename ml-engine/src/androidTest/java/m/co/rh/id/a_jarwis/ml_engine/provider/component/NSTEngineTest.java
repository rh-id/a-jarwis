package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class NSTEngineTest {

    private static NSTEngine mNSTEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        mNSTEngine = mMLEngineProvider.get(NSTEngine.class);
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
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.amber));
        Bitmap bitmap1 = mNSTEngine.applyMosaic(bitmap);
        File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyMosaic.jpg");
        bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(tempFile));
        bitmap1.recycle();
    }

    @Test
    public void applyCandy() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.amber));
        Bitmap bitmap1 = mNSTEngine.applyCandy(bitmap);
        File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyCandy.jpg");
        bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(tempFile));
        bitmap1.recycle();
    }

    @Test
    public void applyRainPrincess() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.amber));
        Bitmap bitmap1 = mNSTEngine.applyRainPrincess(bitmap);
        File tempFile = mMLEngineProvider.get(FileHelper.class).createTempFile("NSTEngineTest_applyRainPrincess.jpg");
        bitmap1.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(tempFile));
        bitmap1.recycle();
    }

}