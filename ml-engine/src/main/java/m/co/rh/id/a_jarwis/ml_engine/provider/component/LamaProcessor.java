package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;

/**
 * Processor of the LaMa image inpainting model, fills the masked region of an
 * image with synthesized content (the model inpaints the largest hole, so the
 * caller crops the image around the mask, see
 * {@link MagicEraserEngine#eraseRegion}).
 * <p>
 * Model contract (verified against the opencv_zoo lama.py reference): two
 * named inputs - image (1,3,512,512 float BGR scaled to 0..1, blobFromImage
 * with scale 1/255, mean 0, no channel swap because the caller hands over an
 * already BGR Mat) and mask (1,1,512,512 float binarized to exactly 0/1; the
 * reference resizes the mask to the model input size first and thresholds
 * afterwards, replicating that order matters: thresholding before the resize
 * would reintroduce intermediate gray values through the bilinear
 * interpolation of the resize). Output (1,3,512,512) in the 0..255 range,
 * channel-first (CHW), converted back to a HWC image with a saturating
 * Mat.convertTo (the reference numpy wrap-cast can silently wrap out of range
 * values, the saturating conversion clamps them instead).
 * <p>
 * A processor instance must never have its {@link #inpaint(Mat, Mat)}
 * invoked concurrently, callers are expected to serialize their calls on one
 * instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache recreates a fresh
 * processor on demand.
 */
class LamaProcessor {
    private static final String TAG = "LamaProcessor";
    /**
     * The model input resolution, the model is trained on an aspect distorting
     * resize at this size (no letterbox padding), so the aspect distortion here
     * matches the reference preprocessing and no padding is added
     */
    private static final int MODEL_INPUT_SIZE = 512;
    /**
     * Name of the image input tensor (see the model contract in the class javadoc)
     */
    private static final String INPUT_IMAGE = "image";
    /**
     * Name of the mask input tensor (see the model contract in the class javadoc)
     */
    private static final String INPUT_MASK = "mask";
    /**
     * Compile-time gate of the spike diagnostics (see {@link #spikeLog()}):
     * false in committed code, flipped to true only temporarily for a
     * connectedCheck spike run
     */
    private static final boolean SPIKE_LOG = false;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public LamaProcessor(String modelPath, ILogger logger) {
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
     * the net wrapper's finalizer once this processor drops its reference —
     * {@link MLEngineInstance} nudges the runtime right after releasing so
     * the reclamation happens at that predictable point instead of an
     * arbitrary later GC.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache recreates a fresh processor
     * on demand (the model file is re-parsed, a seconds-long operation).
     * Releasing more than once is a safe no-op. Synchronized on the same
     * monitor as {@link #inpaint(Mat, Mat)}, so a release can never run
     * concurrently with an inference (that would be a native use-after-free
     * when a page dispose races an in-flight task).
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
     * Inpaint (fill) the masked region of the given image crop.
     *
     * @param bgrCrop  image crop to be inpainted, BGR 3-channel CV_8U
     *                 (the caller converts), never released by this method
     * @param grayMask single channel mask of the region to fill, same
     *                 dimensions as bgrCrop, any positive pixel marks the
     *                 region, never released by this method
     * @return the inpainted image crop matching the bgrCrop dimensions
     * (CV_8U BGR 3-channel), the caller owns it and must release() it
     */
    public synchronized Mat inpaint(Mat bgrCrop, Mat grayMask) {
        checkNotReleased();
        int width = bgrCrop.cols();
        int height = bgrCrop.rows();
        Mat imageBlob = null;
        Mat maskBlob = null;
        Mat output = null;
        Mat result;
        try {
            // the image is already BGR (converted by the caller), no swapRB
            imageBlob = Dnn.blobFromImage(bgrCrop, 1.0 / 255.0,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    new Scalar(0, 0, 0), false, false);
            // blobFromImage resizes the mask inside, the binarization to the exact
            // 0/1 values the model expects happens AFTER the resize (see the class
            // javadoc: resize first, threshold afterwards, like the reference)
            maskBlob = Dnn.blobFromImage(grayMask, 1.0,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    new Scalar(0), false, false);
            binarizeMaskBlob(maskBlob);
            mLogger.d(mTag, "preProcess_imageBlob:" + imageBlob);
            mModelNet.setInput(imageBlob, INPUT_IMAGE);
            mModelNet.setInput(maskBlob, INPUT_MASK);
            output = mModelNet.forward();
            result = postProcess(output, width, height);
        } finally {
            // every Mat is released on every path: an inference failing with a
            // native "Insufficient memory" CvException must not leak the blobs
            if (imageBlob != null) {
                imageBlob.release();
            }
            if (maskBlob != null) {
                maskBlob.release();
            }
            if (output != null) {
                output.release();
            }
        }
        mLogger.d(mTag, "postProcess_result:" + result);
        return result;
    }

    /**
     * Threshold the mask blob to the exact 0/1 float values the model expects
     * (any positive pixel becomes 1.0). The blob is (1,3,512,512): blobFromImage
     * replicated the single channel mask into 3 channels, all channels are
     * binarized the same way. Bulk get/put on n-dimensional Mats silently
     * reads/writes zeros in the OpenCV 5.0.0.1 Java bindings (an all-zero
     * mask would make the inpainting a silent no-op), so the access goes
     * through the reshaped 1x(3*512*512) 2-D view sharing the same memory.
     */
    private static void binarizeMaskBlob(Mat maskBlob) {
        Mat maskFlat = maskBlob.reshape(1, 1);
        int total = (int) maskFlat.total();
        float[] values = new float[total];
        maskFlat.get(0, 0, values);
        int positiveCount = 0;
        for (int i = 0; i < total; i++) {
            if (values[i] > 0) {
                values[i] = 1f;
                positiveCount++;
            } else {
                values[i] = 0f;
            }
        }
        maskFlat.put(0, 0, values);
        spikeLog("mask blob binarized: " + positiveCount + " of " + total
                + " values positive (0 would make the inpainting a no-op)");
    }

    /**
     * Spike diagnostics printer, dead code unless {@link #SPIKE_LOG} is
     * flipped to true for a spike run: the mLogger debug level is not a
     * release gate (the file logger still writes debug messages in release
     * builds, see BaseProviderModule), so these clearly marked System.out
     * lines stay behind a compile-time constant. They exist because the
     * mLogger output does not reach logcat from the instrumented test
     * process, the connectedCheck spike run iterates from them.
     */
    private static void spikeLog(String message) {
        if (SPIKE_LOG) {
            System.out.println("SPIKE: " + message);
        }
    }

    /**
     * Convert the (1,3,512,512) 0..255 channel-first output back to a
     * channel-last image: imagesFromBlob unpacks the batch blob into
     * per-image mats, the BGR planes are split out of it and merged into a
     * 3-channel 512x512 HWC mat (the same idiom PersonSegProcessor uses to
     * split the per-image output planes), then converted to CV_8U with a
     * saturating convertTo (see the class javadoc why the saturating
     * conversion is safer than the reference wrap-cast) and resized back to
     * the crop size.
     */
    private static Mat postProcess(Mat output, int width, int height) {
        List<Mat> images = new ArrayList<>();
        Dnn.imagesFromBlob(output, images);
        // imagesFromBlob returns views into the output blob memory, not copies,
        // the output Mat must stay alive until the channels are split out of it
        Mat perImage = images.get(0);
        List<Mat> channels = new ArrayList<>();
        Mat floatBgr = null;
        Mat result = null;
        try {
            Core.split(perImage, channels);
            floatBgr = new Mat();
            Core.merge(channels, floatBgr);
            result = new Mat();
            // saturating conversion: values outside the 0..255 range clamp instead
            // of wrapping around
            floatBgr.convertTo(result, CvType.CV_8U);
        } finally {
            // the split fills the list incrementally, on a failure the channels
            // allocated so far are released as well
            for (Mat channel : channels) {
                channel.release();
            }
            channels.clear();
            if (floatBgr != null) {
                floatBgr.release();
            }
        }
        try {
            Imgproc.resize(result, result, new Size(width, height),
                    0, 0, Imgproc.INTER_LINEAR);
            return result;
        } catch (RuntimeException | OutOfMemoryError e) {
            // the caller never receives a partially processed image
            result.release();
            throw e;
        }
    }
}
