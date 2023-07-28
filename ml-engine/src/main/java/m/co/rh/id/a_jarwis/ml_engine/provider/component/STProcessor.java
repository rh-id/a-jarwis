package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;

class STProcessor {
    private final ILogger mLogger;
    private final Net mModelNet;
    private final String mTag;

    public STProcessor(String modelPath, ILogger logger) {
        mModelNet = Dnn.readNetFromONNX(modelPath);
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
    }

    public Bitmap process(Bitmap input) {
        Mat mat = new Mat();
        Utils.bitmapToMat(input, mat);
        int width = mat.cols();
        int height = mat.rows();
        double scale = 540.0 / Math.max(width, height);
        mat = preProcess(mat, scale);
        mLogger.d(mTag, "preProcess_mat:" + mat);
        mModelNet.setInput(mat);
        mat = mModelNet.forward();
        return postProcess(mat, scale);
    }

    private Mat preProcess(Mat image, double scale) {
        Mat mat = new Mat();
        Imgproc.cvtColor(image, mat, Imgproc.COLOR_RGBA2RGB);
        Imgproc.resize(mat, mat, new Size(mat.cols() * scale, mat.rows() * scale));
        return Dnn.blobFromImage(mat);
    }

    private Bitmap postProcess(Mat image, double scale) {
        int height = (int) (image.size(2) / scale);
        int width = (int) (image.size(3) / scale);
        List<Mat> images = new ArrayList<>();
        Dnn.imagesFromBlob(image, images);
        Mat mat = images.get(0);
        mat.convertTo(mat, CvType.CV_8U);
        Imgproc.resize(mat, mat, new Size(width, height));
        Bitmap bitmap = Bitmap.createBitmap(width,
                height, Bitmap.Config.ARGB_8888);
        mLogger.d(mTag, "postProcess_mat:" + mat);
        Utils.matToBitmap(mat, bitmap);
        return bitmap;
    }
}
