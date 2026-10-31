package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import org.opencv.core.Mat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
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
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider,
                ModelType.FACE_DETECT, ModelType.FACE_RECOGNIZER);
        mFaceEngine = mMLEngineProvider.get(FaceEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
    }

    @Test
    public void faceEngineDetect() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abbas_kiarostami_0001)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            List<Rect> rectList = mFaceEngine.detectFace(bitmap);
            assertEquals(1, rectList.size());
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void faceEngineRecognize() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap face1 = null;
        Bitmap face2 = null;
        Bitmap face1Crop = null;
        Bitmap face2Crop = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abel_pacheco_0001)) {
                face1 = BitmapFactory.decodeStream(in);
            }
            List<Rect> face1List = mFaceEngine.detectFace(face1);
            assertEquals(1, face1List.size());

            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abel_pacheco_0002)) {
                face2 = BitmapFactory.decodeStream(in);
            }
            List<Rect> face2List = mFaceEngine.detectFace(face2);
            assertEquals(1, face2List.size());

            List<Rect> faceSimilar = mFaceEngine.searchFace(face1, face2);
            assertEquals(1, faceSimilar.size());

            Rect face1Rect = face1List.get(0);
            Rect face2Rect = face2List.get(0);
            face1Crop = Bitmap.createBitmap(face1, face1Rect.left, face1Rect.top,
                    face1Rect.width(), face1Rect.height());
            face2Crop = Bitmap.createBitmap(face2, face2Rect.left, face2Rect.top,
                    face2Rect.width(), face2Rect.height());
            faceSimilar = mFaceEngine.searchFace(face1Crop, face2Crop);
            assertEquals(1, faceSimilar.size());
        } finally {
            if (face1Crop != null) {
                face1Crop.recycle();
            }
            if (face2Crop != null) {
                face2Crop.recycle();
            }
            if (face1 != null) {
                face1.recycle();
            }
            if (face2 != null) {
                face2.recycle();
            }
        }
    }

    @Test
    public void faceEngineRecognize_faceNotSimilar() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap face1 = null;
        Bitmap face2 = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abel_pacheco_0001)) {
                face1 = BitmapFactory.decodeStream(in);
            }
            List<Rect> face1List = mFaceEngine.detectFace(face1);
            assertEquals(1, face1List.size());

            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abbas_kiarostami_0001)) {
                face2 = BitmapFactory.decodeStream(in);
            }
            List<Rect> face2List = mFaceEngine.detectFace(face2);
            assertEquals(1, face2List.size());

            List<Rect> faceSimilar = mFaceEngine.searchFace(face1, face2);
            assertEquals(0, faceSimilar.size());
        } finally {
            if (face1 != null) {
                face1.recycle();
            }
            if (face2 != null) {
                face2.recycle();
            }
        }
    }

    @Test
    public void faceEngine_isFaceSimilar() throws IOException {
        Context context = mMLEngineProvider.getContext();
        Bitmap face1 = null;
        Bitmap face2 = null;
        Mat face1List = null;
        Mat face2List = null;
        Mat face1Row = null;
        Mat face2Row = null;
        try {
            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abel_pacheco_0001)) {
                face1 = BitmapFactory.decodeStream(in);
            }
            face1List = mFaceEngine.detectFaceRaw(face1);
            assertEquals(1, face1List.rows());

            try (InputStream in = context.getResources().
                    openRawResource(R.raw.abel_pacheco_0002)) {
                face2 = BitmapFactory.decodeStream(in);
            }
            face2List = mFaceEngine.detectFaceRaw(face2);
            assertEquals(1, face2List.rows());

            face1Row = face1List.row(0);
            face2Row = face2List.row(0);
            boolean faceSimilar = mFaceEngine.isFaceSimilar(face1, face2,
                    face1Row, face2Row);
            assertTrue(faceSimilar);
        } finally {
            // detectFaceRaw documents a caller-owned result — release it like any Mat
            if (face1Row != null) {
                face1Row.release();
            }
            if (face2Row != null) {
                face2Row.release();
            }
            if (face1List != null) {
                face1List.release();
            }
            if (face2List != null) {
                face2List.release();
            }
            if (face1 != null) {
                face1.recycle();
            }
            if (face2 != null) {
                face2.recycle();
            }
        }
    }
}