package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import androidx.annotation.NonNull;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;
import java.io.IOException;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class MLEngineInstance {
    private static final String TAG = "MLEngineInstance";

    public static final String BASE_PATH = "/ml-engine/engine";
    public static final String FACE_PATH = BASE_PATH + "/face";
    public static final String FACE_DETECT_PATH = FACE_PATH + "/detect";
    public static final String FACE_DETECT_FILE = "face_detection_yunet_2023mar.onnx";
    public static final int FACE_DETECT_WIDTH = 640;
    public static final int FACE_DETECT_HEIGHT = 640;
    public static final String FACE_RECOGNIZER_PATH = FACE_PATH + "/recognizer";
    public static final String FACE_RECOGNIZER_FILE = "face_recognition_sface_2021dec_int8.onnx";
    public static final String NEURAL_STYLE_TRANSFER_PATH = BASE_PATH + "/neural-style-transfer";
    public static final String NST_MOSAIC_FILE = "nst_mosaic_9.onnx";
    public static final String NST_CANDY_FILE = "nst_candy_9.onnx";
    public static final String NST_RAIN_PRINCESS_FILE = "nst_rain_princess_9.onnx";
    public static final String NST_UDNIE_FILE = "nst_udnie_9.onnx";
    private final Context mAppContext;
    private final ILogger mLogger;
    private final FileHelper mFileHelper;

    public MLEngineInstance(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mFileHelper = provider.get(FileHelper.class);
        if (OpenCVLoader.initDebug()) {
            mLogger.d("OpenCV", "OpenCV loaded");
        } else {
            mLogger.e("OpenCV", "Error Loading OpenCV");
        }
        initFaceDetector();
        initFaceRecognizer();
        initNSTMosaic();
        initNSTCandy();
        initNSTRainPrincess();
        initNSTUdnie();
    }

    public NSTProcessor getNSTUdnie() {
        return loadNSTUdnie();
    }

    private NSTProcessor loadNSTUdnie() {
        File NSTFile = initNSTUdnie();
        return new NSTProcessor(NSTFile.getAbsolutePath(), mLogger);
    }

    @NonNull
    private File initNSTUdnie() {
        File nstParent = new File(mAppContext.getFilesDir(), NEURAL_STYLE_TRANSFER_PATH);
        File udnieFile = new File(nstParent, "/" + NST_UDNIE_FILE);
        if (!udnieFile.exists()) {
            try {
                nstParent.mkdirs();
                udnieFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.nst_udnie_9, udnieFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading NST Udnie: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return udnieFile;
    }

    public NSTProcessor getNSTRainPrincess() {
        return loadNSTRainPrincess();
    }

    private NSTProcessor loadNSTRainPrincess() {
        File NSTFile = initNSTRainPrincess();
        return new NSTProcessor(NSTFile.getAbsolutePath(), mLogger);
    }

    @NonNull
    private File initNSTRainPrincess() {
        File nstParent = new File(mAppContext.getFilesDir(), NEURAL_STYLE_TRANSFER_PATH);
        File rainPrincessFile = new File(nstParent, "/" + NST_RAIN_PRINCESS_FILE);
        if (!rainPrincessFile.exists()) {
            try {
                nstParent.mkdirs();
                rainPrincessFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.nst_rain_princess_9, rainPrincessFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading NST Rain Princess: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return rainPrincessFile;
    }

    public NSTProcessor getNSTCandy() {
        return loadNSTCandy();
    }

    private NSTProcessor loadNSTCandy() {
        File NSTFile = initNSTCandy();
        return new NSTProcessor(NSTFile.getAbsolutePath(), mLogger);
    }

    @NonNull
    private File initNSTCandy() {
        File nstParent = new File(mAppContext.getFilesDir(), NEURAL_STYLE_TRANSFER_PATH);
        File candyFile = new File(nstParent, "/" + NST_CANDY_FILE);
        if (!candyFile.exists()) {
            try {
                nstParent.mkdirs();
                candyFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.nst_candy_9, candyFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading NST Candy: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return candyFile;
    }

    public NSTProcessor getNSTMosaic() {
        return loadNSTMosaic();
    }

    private NSTProcessor loadNSTMosaic() {
        File NSTFile = initNSTMosaic();
        return new NSTProcessor(NSTFile.getAbsolutePath(), mLogger);
    }

    @NonNull
    private File initNSTMosaic() {
        File nstParent = new File(mAppContext.getFilesDir(), NEURAL_STYLE_TRANSFER_PATH);
        File mosaicFile = new File(nstParent, "/" + NST_MOSAIC_FILE);
        if (!mosaicFile.exists()) {
            try {
                nstParent.mkdirs();
                mosaicFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.nst_mosaic_9, mosaicFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading NST Mosaic: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return mosaicFile;
    }

    public FaceRecognizerSF getFaceRecognizerModel() {
        return loadFaceRecognizer();
    }

    public FaceDetectorYN getFaceDetectModel() {
        return loadFaceDetect();
    }

    private FaceRecognizerSF loadFaceRecognizer() {
        File faceRecognizerFile = initFaceRecognizer();
        return FaceRecognizerSF.create(faceRecognizerFile.getAbsolutePath(), "");
    }

    private FaceDetectorYN loadFaceDetect() {
        File faceDetectFile = initFaceDetector();
        return FaceDetectorYN.create(faceDetectFile.getAbsolutePath(), "", new Size(FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT),
                0.6f, 0.5f);
    }

    @NonNull
    private File initFaceDetector() {
        File faceDetectParent = new File(mAppContext.getFilesDir(), FACE_DETECT_PATH);
        File faceDetectFile = new File(faceDetectParent, "/" + FACE_DETECT_FILE);
        if (!faceDetectFile.exists()) {
            try {
                faceDetectParent.mkdirs();
                faceDetectFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.face_detection_yunet_2023mar, faceDetectFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading face detector: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return faceDetectFile;
    }

    @NonNull
    private File initFaceRecognizer() {
        File faceRecognizerParent = new File(mAppContext.getFilesDir(), FACE_RECOGNIZER_PATH);
        File faceRecognizerFile = new File(faceRecognizerParent, "/" + FACE_RECOGNIZER_FILE);
        if (!faceRecognizerFile.exists()) {
            try {
                faceRecognizerParent.mkdirs();
                faceRecognizerFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.face_recognition_sface_2021dec_int8, faceRecognizerFile);
            } catch (IOException e) {
                mLogger.e(TAG, "Failed loading face recognizer: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        return faceRecognizerFile;
    }
}
