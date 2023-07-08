package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;

import java.io.File;
import java.io.IOException;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class MLEngineInstance {
    private static final String TAG = "MLEngineInstance";

    public static final String FACE_PATH = "/ml-engine/engine/face";
    public static final String FACE_DETECT_PATH = FACE_PATH + "/detect";
    public static final String FACE_DETECT_FILE = "face_detection_yunet_2023mar.onnx";
    public static final int FACE_DETECT_WIDTH = 640;
    public static final int FACE_DETECT_HEIGHT = 640;
    private Context mAppContext;
    private ILogger mLogger;
    private FileHelper mFileHelper;
    private FaceDetectorYN mFaceDetectModel;

    public MLEngineInstance(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mFileHelper = provider.get(FileHelper.class);
        if (OpenCVLoader.initDebug()) {
            mLogger.d("OpenCV", "OpenCV loaded");
        } else {
            mLogger.e("OpenCV", "Error Loading OpenCV");
        }
        mFaceDetectModel = loadFaceDetect();
    }

    public FaceDetectorYN getFaceDetectModel() {
        return mFaceDetectModel;
    }

    private FaceDetectorYN loadFaceDetect() {
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
        return FaceDetectorYN.create(faceDetectFile.getAbsolutePath(), "", new Size(FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT),
                0.6f, 0.5f);
    }
}
