package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.hoko.blur.HokoBlur;
import com.hoko.blur.processor.BlurProcessor;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import m.co.rh.id.a_jarwis.ml_engine.workmanager.BlurFaceWorkRequest;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class FaceEngine {
    private static final String TAG = "FaceEngine";
    private final ILogger mLogger;
    private final WorkManager mWorkManager;
    private final ExecutorService mExecutorService;
    private final ProviderValue<MLEngineInstance> mEngineInstance;
    private final BlurProcessor mBlurProcessor;

    public FaceEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mWorkManager = provider.get(WorkManager.class);
        mExecutorService = provider.get(ExecutorService.class);
        mEngineInstance = provider.lazyGet(MLEngineInstance.class);
        mBlurProcessor = HokoBlur.with(provider.getContext().getApplicationContext())
                .scheme(HokoBlur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
                .mode(HokoBlur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
                .radius(25) //blur radius，max=25，default=5
                .sampleFactor(5f) //scale factor，if factor=2，the width and height of a originalBitmap will be scale to 1/2 sizes，default=5
                .forceCopy(false) //If scale factor=1.0f，the origin originalBitmap will be modified. You could set forceCopy=true to avoid it. default=false
                .needUpscale(false) //After blurring，the originalBitmap will be upscaled to origin sizes，default=true
                .translateX(0)//add x axis offset when blurring
                .translateY(0)//add y axis offset when blurring
                .processor(); //build a blur processor
    }

    /**
     * Process bitmap using face detection engine and return a list of rectangle indicating location of faces
     */
    public List<Rect> detectFace(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        int imgWidth = bitmap.getWidth();
        int imgHeight = bitmap.getHeight();
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, MLEngineInstance.FACE_DETECT_WIDTH,
                MLEngineInstance.FACE_DETECT_HEIGHT, false);
        Mat faceInput = new Mat();
        Utils.bitmapToMat(resized, faceInput);
        Imgproc.cvtColor(faceInput, faceInput, Imgproc.COLOR_BGR2RGB);
        FaceDetectorYN faceDetectorYN = mEngineInstance.get().getFaceDetectModel();
        Mat faceOutput = new Mat();
        faceDetectorYN.detect(faceInput, faceOutput);
        int totalFace = faceOutput.rows();
        for (int i = 0; i < totalFace; i++) {
            int x = (int) ((faceOutput.get(i, 0)[0] / MLEngineInstance.FACE_DETECT_WIDTH) * imgWidth);
            int y = (int) ((faceOutput.get(i, 1)[0] / MLEngineInstance.FACE_DETECT_HEIGHT)* imgHeight);
            int w = (int) ((faceOutput.get(i, 2)[0] / MLEngineInstance.FACE_DETECT_WIDTH) * imgWidth);
            int h = (int) ((faceOutput.get(i, 3)[0] / MLEngineInstance.FACE_DETECT_HEIGHT) * imgHeight);
            double score = faceOutput.get(i, 14)[0];
            mLogger.d(TAG, "face_" + i + ": " + x + "," + y + "," + w + "," + h + ";" + score);
            rectList.add(new Rect(x, y, x + w, y + h));
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

    private Bitmap cropBitmap(final Bitmap originalBmp, Rect dest) {
        return Bitmap.createBitmap(originalBmp, dest.left, dest.top, dest.width(), dest.height());
    }
}
