package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import androidx.annotation.NonNull;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
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
    public static final String NST_MOSAIC_FILE = "mosaic-9.onnx";
    public static final String NST_CANDY_FILE = "candy-9.onnx";
    public static final String NST_RAIN_PRINCESS_FILE = "rain-princess-9.onnx";
    public static final String NST_UDNIE_FILE = "udnie-9.onnx";
    public static final String NST_POINTILISM_FILE = "pointilism-9.onnx";
    private final Context mAppContext;
    private final ILogger mLogger;

    public MLEngineInstance(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        if (OpenCVLoader.initLocal()) {
            mLogger.d("OpenCV", "OpenCV loaded");
        } else {
            mLogger.e("OpenCV", "Error Loading OpenCV");
        }
    }

    public STProcessor getNSTPointilism() {
        return new STProcessor(requireModelFile(ModelType.NST_POINTILISM).getAbsolutePath(), mLogger);
    }

    public STProcessor getNSTUdnie() {
        return new STProcessor(requireModelFile(ModelType.NST_UDNIE).getAbsolutePath(), mLogger);
    }

    public STProcessor getNSTRainPrincess() {
        return new STProcessor(requireModelFile(ModelType.NST_RAIN_PRINCESS).getAbsolutePath(), mLogger);
    }

    public STProcessor getNSTCandy() {
        return new STProcessor(requireModelFile(ModelType.NST_CANDY).getAbsolutePath(), mLogger);
    }

    public STProcessor getNSTMosaic() {
        return new STProcessor(requireModelFile(ModelType.NST_MOSAIC).getAbsolutePath(), mLogger);
    }

    public FaceRecognizerSF getFaceRecognizerModel() {
        return FaceRecognizerSF.create(
                requireModelFile(ModelType.FACE_RECOGNIZER).getAbsolutePath(), "");
    }

    public FaceDetectorYN getFaceDetectModel() {
        return FaceDetectorYN.create(
                requireModelFile(ModelType.FACE_DETECT).getAbsolutePath(), "",
                new Size(FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT), 0.6f, 0.5f);
    }

    public boolean isFaceDetectAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.FACE_DETECT);
    }

    public boolean isFaceRecognizerAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.FACE_RECOGNIZER);
    }

    public boolean isNSTAvailable(int theme) {
        return ModelCatalog.isAvailable(mAppContext, ModelCatalog.fromTheme(theme));
    }

    /**
     * These models are downloaded on demand (see ModelDownloadWorker),
     * callers are expected to check availability first (see ModelCatalog.isAvailable),
     * hence this should never be hit on normal flow
     */
    @NonNull
    private File requireModelFile(ModelType modelType) {
        // isAvailable also runs the legacy file name migration when needed
        if (!ModelCatalog.isAvailable(mAppContext, modelType)) {
            File file = ModelCatalog.getModelFile(mAppContext, modelType);
            String message = "Model not available: " + modelType.getDisplayName()
                    + ", expected file: " + file.getAbsolutePath()
                    + ". Please download the model first.";
            mLogger.e(TAG, message);
            throw new IllegalStateException(message);
        }
        return ModelCatalog.getModelFile(mAppContext, modelType);
    }
}
