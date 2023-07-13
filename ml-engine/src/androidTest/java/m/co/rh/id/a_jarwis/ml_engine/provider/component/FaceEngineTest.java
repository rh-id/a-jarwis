package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class FaceEngineTest {

    private static FaceEngine mFaceEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        mFaceEngine = mMLEngineProvider.get(FaceEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
    }

    @Test
    public void faceEngineDetect() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.abbas_kiarostami_0001));
        List<Rect> rectList = mFaceEngine.detectFace(bitmap);
        assertEquals(1, rectList.size());
    }

    @Test
    public void faceEngineRecognize() {
        Context context = mMLEngineProvider.getContext();
        Bitmap face1 = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.abel_pacheco_0001));
        List<Rect> face1List = mFaceEngine.detectFace(face1);
        assertEquals(1, face1List.size());

        Bitmap face2 = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.abel_pacheco_0002));
        List<Rect> face2List = mFaceEngine.detectFace(face2);
        assertEquals(1, face2List.size());

        List<Rect> faceSimilar = mFaceEngine.searchFace(face1, face2);
        assertEquals(1, faceSimilar.size());

        Rect face1Rect = face1List.get(0);
        Rect face2Rect = face2List.get(0);
        faceSimilar = mFaceEngine.searchFace(
                Bitmap.createBitmap(face1, face1Rect.left, face1Rect.top, face1Rect.width(), face1Rect.height()),
                Bitmap.createBitmap(face2, face2Rect.left, face2Rect.top, face2Rect.width(), face2Rect.height()));
        assertEquals(1, faceSimilar.size());
    }

    @Test
    public void faceEngineRecognize_faceNotSimilar(){
        Context context = mMLEngineProvider.getContext();
        Bitmap face1 = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.abel_pacheco_0001));
        List<Rect> face1List = mFaceEngine.detectFace(face1);
        assertEquals(1, face1List.size());

        Bitmap face2 = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.abbas_kiarostami_0001));
        List<Rect> face2List = mFaceEngine.detectFace(face2);
        assertEquals(1, face2List.size());

        List<Rect> faceSimilar = mFaceEngine.searchFace(face1, face2);
        assertEquals(0, faceSimilar.size());
    }
}