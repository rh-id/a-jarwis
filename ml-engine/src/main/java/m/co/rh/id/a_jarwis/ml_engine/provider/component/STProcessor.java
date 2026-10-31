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

/**
 * Processor of the neural style transfer models (the themes defined in
 * {@link STEngine}), restyles the given bitmap with the theme's model.
 * <p>
 * A processor instance must never have its {@link #process(Bitmap)}
 * invoked concurrently, callers are expected to serialize their calls on one
 * instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache evicts instances
 * only through dispose(), the next getSTProcessor call then creates a fresh
 * processor.
 */
class STProcessor {
    private static final String TAG = "STProcessor";
    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public STProcessor(String modelPath, ILogger logger) {
        mModelNet = Dnn.readNetFromONNX(modelPath);
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
    }

    /**
     * Release the native network of this processor: the parsed model weights
     * plus the large dnn workspace OpenCV retains inside the net after the
     * forward() calls. The OpenCV 5.0.0.1 Java bindings expose no public
     * native delete for cv::dnn::Net ({@code Net.finalizeNet()} only prepares
     * the inference session, it frees nothing), the native peer is deleted by
     * the net wrapper's finalizer once this processor drops its reference.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache evicts instances only
     * through dispose(), after which the next getSTProcessor call creates
     * a fresh processor (the model file is re-parsed, a seconds-long
     * operation). Releasing more than once is a safe no-op.
     * Synchronized on the same monitor as {@link #process(Bitmap)}, so a
     * release can never run concurrently with an inference (that would be a
     * native use-after-free when a page dispose races an in-flight task).
     */
    public synchronized void release() {
        if (mModelNet == null) {
            return;
        }
        // this was the last strong reference to the net wrapper
        mModelNet = null;
    }

    /**
     * A released processor holds no net anymore: using it would be a bug of
     * the caller (a released instance must be discarded, the engine instance
     * recreates it on demand), fail fast with a clear message instead of a
     * null native pointer deep inside the inference
     */
    private void checkNotReleased() {
        if (mModelNet == null) {
            throw new IllegalStateException(TAG
                    + " already released, a released processor must be"
                    + " discarded (MLEngineInstance recreates it on demand)");
        }
    }

    /**
     * Restyle the given bitmap with this processor's model.
     *
     * @param input bitmap to be styled, never recycled by this method
     * @return the stylized bitmap, the caller owns it
     */
    public synchronized Bitmap process(Bitmap input) {
        checkNotReleased();
        Mat rgba = null;
        Mat blob = null;
        Mat output = null;
        Bitmap result;
        try {
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            int width = rgba.cols();
            int height = rgba.rows();
            double scale = 540.0 / Math.max(width, height);
            blob = preProcess(rgba, scale);
            mLogger.d(mTag, "preProcess_mat:" + blob);
            mModelNet.setInput(blob);
            output = mModelNet.forward();
            result = postProcess(output, scale);
        } finally {
            // every Mat is released on every path: an inference failing with a
            // native "Insufficient memory" CvException must not leak the input
            // image, the input blob or the forward output
            if (rgba != null) {
                rgba.release();
            }
            if (blob != null) {
                blob.release();
            }
            if (output != null) {
                output.release();
            }
        }
        return result;
    }

    /**
     * Build the model input blob from the RGBA image: convert to RGB, resize
     * by the given scale and wrap into a blob (the full-resolution RGBA image
     * stays owned by {@link #process(Bitmap)} and is released by its finally).
     * The RGB intermediate is dead once the blob exists, it is released on
     * every path.
     */
    private Mat preProcess(Mat image, double scale) {
        Mat mat = new Mat();
        try {
            Imgproc.cvtColor(image, mat, Imgproc.COLOR_RGBA2RGB);
            Imgproc.resize(mat, mat, new Size(mat.cols() * scale, mat.rows() * scale));
            return Dnn.blobFromImage(mat);
        } finally {
            mat.release();
        }
    }

    /**
     * Convert the (1,C,H,W) channel-first output blob back to a channel-last
     * bitmap: imagesFromBlob unpacks the batch blob into per-image mats, the
     * per-image mat is converted to CV_8U with a saturating convertTo and
     * resized back towards the input resolution. The unpacked mats are
     * released on every path, the blob itself must outlive the unpacking
     * (the per-image mats share its memory before the conversion) and is
     * released by the {@link #process(Bitmap)} finally after this returns.
     */
    private Bitmap postProcess(Mat image, double scale) {
        int height = (int) (image.size(2) / scale);
        int width = (int) (image.size(3) / scale);
        List<Mat> images = new ArrayList<>();
        try {
            Dnn.imagesFromBlob(image, images);
            Mat mat = images.get(0);
            mat.convertTo(mat, CvType.CV_8U);
            Imgproc.resize(mat, mat, new Size(width, height));
            Bitmap bitmap = Bitmap.createBitmap(width,
                    height, Bitmap.Config.ARGB_8888);
            mLogger.d(mTag, "postProcess_mat:" + mat);
            Utils.matToBitmap(mat, bitmap);
            return bitmap;
        } finally {
            for (Mat mat : images) {
                mat.release();
            }
            images.clear();
        }
    }
}
