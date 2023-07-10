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
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.workmanager.BlurFaceWorkRequest;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class FaceEngine {
    private static final String TAG = "FaceEngine";
    private final ILogger mLogger;
    private final WorkManager mWorkManager;
    private final ProviderValue<MLEngineInstance> mEngineInstance;
    private final BlurProcessor mBlurProcessor;

    public FaceEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mWorkManager = provider.get(WorkManager.class);
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
     * Check if faceImage detected in imageToBeSearch or not
     *
     * @param faceImage       image to be checked expected contains one face
     * @param imageToBeSearch image to be scanned
     * @return list of rectangle that this engine recognize from imageToBeSearch
     */
    public List<Rect> searchFace(Bitmap faceImage, Bitmap imageToBeSearch) {
        List<Rect> resultList = new ArrayList<>();
        Mat faceLoc = detectFaceRaw(faceImage);
        if (faceLoc.rows() > 1) {
            throw new IllegalArgumentException("Face to be searched must be one only");
        }
        Mat imageSearch = detectFaceRaw(imageToBeSearch);
        int rowSize = imageSearch.rows();
        if (rowSize > 0) {
            for (int i = 0; i < rowSize; i++) {
                Mat detectedFace = imageSearch.row(i);
                boolean faceSimilar = isFaceSimilar(faceImage, imageToBeSearch,
                        faceLoc, detectedFace);
                if (faceSimilar) {
                    resultList.add(toRect(detectedFace));
                }
            }
        }
        return resultList;
    }

    /**
     * Process bitmap using face detection engine and return a list of rectangle indicating location of faces
     */
    public List<Rect> detectFace(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        Mat faceOutput = detectFaceRaw(bitmap);
        int totalFace = faceOutput.rows();
        for (int i = 0; i < totalFace; i++) {
            rectList.add(toRect(faceOutput.row(i)));
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
            for (Rect rect : rectList) {
                Bitmap faceCrop = cropBitmap(originalBitmap, rect);
                Bitmap blurredFace = mBlurProcessor.blur(faceCrop);
                canvas.drawBitmap(blurredFace, null, rect, null);
                faceCrop.recycle();
                blurredFace.recycle();
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

    /**
     * Compare 2 faces and check if it is similar
     *
     * @return true if similar, otherwise false
     */
    private boolean isFaceSimilar(Bitmap image1Src, Bitmap image2Src,
                                  Mat face1Loc, Mat face2Loc) {
        FaceRecognizerSF faceRecognizerSF = mEngineInstance.get().getFaceRecognizerModel();
        Mat image1 = new Mat();
        Mat image2 = new Mat();
        Utils.bitmapToMat(image1Src, image1);
        Utils.bitmapToMat(image2Src, image2);
        Imgproc.cvtColor(image1, image1, Imgproc.COLOR_BGR2RGB);
        Imgproc.cvtColor(image2, image2, Imgproc.COLOR_BGR2RGB);
        Mat alignedF1 = new Mat();
        Mat alignedF2 = new Mat();
        faceRecognizerSF.alignCrop(image1, face1Loc, alignedF1);
        faceRecognizerSF.alignCrop(image2, face2Loc, alignedF2);
        Mat feature1 = new Mat();
        Mat feature2 = new Mat();
        faceRecognizerSF.feature(alignedF1, feature1);
        faceRecognizerSF.feature(alignedF2, feature2);
        double cosScore = faceRecognizerSF.match(feature1, feature2, FaceRecognizerSF.FR_COSINE);
        double L2Score = faceRecognizerSF.match(feature1, feature2, FaceRecognizerSF.FR_NORM_L2);
        /*
            two faces have same identity if the cosine distance is greater than or equal to 0.363,
            or the normL2 distance is less than or equal to 1.128.
         */
        mLogger.d(TAG, "score:" + cosScore + ";" + L2Score);
        return cosScore >= 0.363 || L2Score <= 1.128;
    }

    private Rect toRect(Mat detectedFace) {
        int x = (int) detectedFace.get(0, 0)[0];
        int y = (int) detectedFace.get(0, 1)[0];
        int w = (int) detectedFace.get(0, 2)[0];
        int h = (int) detectedFace.get(0, 3)[0];
        return new Rect(x, y, x + w, y + h);
    }

    private Mat detectFaceRaw(Bitmap bitmap) {
        FaceDetectorYN faceDetectorYN = mEngineInstance.get().getFaceDetectModel();
        faceDetectorYN.setInputSize(new Size(bitmap.getWidth(), bitmap.getHeight()));
        Mat faceInput = new Mat();
        Utils.bitmapToMat(bitmap, faceInput);
        Imgproc.cvtColor(faceInput, faceInput, Imgproc.COLOR_BGR2RGB);
        Mat faceOutput = new Mat();
        faceDetectorYN.detect(faceInput, faceOutput);
        return faceOutput;
    }

    private Bitmap cropBitmap(final Bitmap originalBmp, Rect dest) {
        return Bitmap.createBitmap(originalBmp, dest.left, dest.top, dest.width(), dest.height());
    }
}
