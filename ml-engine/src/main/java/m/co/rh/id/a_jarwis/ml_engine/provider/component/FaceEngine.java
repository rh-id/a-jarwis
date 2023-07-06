package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.hoko.blur.HokoBlur;
import com.hoko.blur.processor.BlurProcessor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import ai.djl.android.core.BitmapImageFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;
import m.co.rh.id.a_jarwis.ml_engine.model.FaceDetectedObjects;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.BlurFaceWorkRequest;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class FaceEngine {
    private static final String TAG = "FaceEngine";
    private Context mAppContext;
    private ILogger mLogger;
    private WorkManager mWorkManager;
    private ExecutorService mExecutorService;
    private MLEngineInstance mEngineInstance;
    private BlurProcessor mBlurProcessor;

    public FaceEngine(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mWorkManager = provider.get(WorkManager.class);
        mExecutorService = provider.get(ExecutorService.class);
        mEngineInstance = provider.get(MLEngineInstance.class);
        mBlurProcessor = HokoBlur.with(mAppContext)
                .scheme(HokoBlur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
                .mode(HokoBlur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
                .radius(15) //blur radius，max=25，default=5
                .sampleFactor(2.0f) //scale factor，if factor=2，the width and height of a originalBitmap will be scale to 1/2 sizes，default=5
                .forceCopy(false) //If scale factor=1.0f，the origin originalBitmap will be modified. You could set forceCopy=true to avoid it. default=false
                .needUpscale(true) //After blurring，the originalBitmap will be upscaled to origin sizes，default=true
                .translateX(0)//add x axis offset when blurring
                .translateY(0)//add y axis offset when blurring
                .processor(); //build a blur processor
    }

    /**
     * Process bitmap using face detection engine and return a list of rectangle indicating location of faces
     */
    public List<Rect> detectFace(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        FaceDetectedObjects faceDetectedObjects = null;
        try (Predictor<Image, FaceDetectedObjects> predictor = mEngineInstance.getFaceDetectModel().newPredictor()) {
            faceDetectedObjects = predictor.predict(fromBitmap(bitmap));
        } catch (Exception e) {
            mLogger.e(TAG, e.getMessage(), e);
        }
        if (faceDetectedObjects != null) {
            int faces = faceDetectedObjects.getNumberOfObjects();
            if (faces > 0) {
                List<BoundingBox> boundingBoxes = faceDetectedObjects.getBoundingBoxes();
                for (int i = 0; i < faces; i++) {
                    BoundingBox boundingBox = boundingBoxes.get(i);
                    Rectangle rectangle = boundingBox.getBounds();
                    double x = rectangle.getX();
                    double y = rectangle.getY();
                    double rightD = x + rectangle.getWidth();
                    double bottomD = y + rectangle.getHeight();
                    int left = (int) x;
                    int top = (int) y;
                    int right = (int) rightD;
                    int bottom = (int) bottomD;
                    rectList.add(new Rect(left, top, right, bottom));
                    mLogger.d(TAG, "rect:" + left + "," + top + "," + right + "," + bottom);
                }
            }
        }

        return rectList;
    }

    /**
     * Process bitmap using face detection engine, blur and process the bitmap.
     *
     * @return blurred image or null if face is not detected
     */
    public Bitmap blurFace(Bitmap originalBitmap) {
        Bitmap result = null;
        List<Rect> rectList = detectFace(originalBitmap);
        if (!rectList.isEmpty()) {
            result = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(originalBitmap, 0, 0, null);
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (Rect rect : rectList) {
                tasks.add(mExecutorService.submit(
                        () -> {
                            Bitmap faceCrop = cropBitmap(originalBitmap, rect);
                            Bitmap blurredFace = mBlurProcessor.blur(faceCrop);
                            canvas.drawBitmap(blurredFace, null, rect, null);
                            faceCrop.recycle();
                            blurredFace.recycle();
                            return true;
                        }
                ));
            }
            while (!tasks.isEmpty()) {
                Future<Boolean> task = tasks.get(0);
                if (task.isDone()) {
                    try {
                        task.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        tasks.remove(task);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Enqueue blur face to be executed in workmanager
     *
     * @param file image file to be executed
     */
    public void enqueueBlurFace(File file) {
        OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(BlurFaceWorkRequest.class)
                .setInputData(
                        new Data.Builder()
                                .putString(Params.ARGS_FILE_PATH, file.getAbsolutePath())
                                .build()
                )
                .build();
        mWorkManager.enqueue(oneTimeWorkRequest);
    }

    public void compareFaces(File imageFile1, File imageFile2) {
        // TODO
    }

    private Bitmap cropBitmap(final Bitmap originalBmp, Rect dest) {
        return Bitmap.createBitmap(originalBmp, dest.left, dest.top, dest.width(), dest.height());
    }

    private Image fromBitmap(Bitmap bitmap) {
        return BitmapImageFactory.getInstance().fromImage(bitmap);
    }
}
