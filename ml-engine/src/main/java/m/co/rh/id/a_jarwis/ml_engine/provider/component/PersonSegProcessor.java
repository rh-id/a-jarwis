package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
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
 * Processor of the PP-HumanSeg portrait segmentation model.
 * Produces a soft person alpha mask (CV_8U, 0..255) at the resolution of the
 * input bitmap: 0 = background, 255 = person, softened towards the middle at
 * the segmentation edge so the mask can be composited without a hard cut.
 * <p>
 * The model output is already a two channel softmax probability map
 * (channel 0 = background, channel 1 = person, the two channels sum to
 * exactly 1.0 per pixel), verified with a region probe on this exact
 * int8bq build. The person alpha is therefore the person channel taken
 * directly: applying an additional sigmoid (as if the channels were raw
 * logits) would compress the whole mask into the 0.27..0.73 gray ghost
 * range and break both compositing modes.
 * <p>
 * A processor instance must never have its {@link #computeMask(Bitmap)}
 * invoked concurrently, callers are expected to serialize their calls on one
 * instance (e.g. through a single-thread executor).
 */
class PersonSegProcessor {
    private static final String TAG = "PersonSegProcessor";
    /**
     * The model input resolution, the model is trained on an aspect distorting
     * resize at this size (no letterbox padding), so the aspect distortion here
     * matches the training preprocessing and no padding is added
     */
    private static final int MODEL_INPUT_SIZE = 192;
    /**
     * Normalization of the model input: OpenCV blobFromImage computes
     * (image - mean) * scaleFactor, so mean 127.5 and scale 1/127.5 map each
     * channel to the -1..1 range the model expects
     */
    private static final double NORMALIZE_SCALE_FACTOR = 1.0 / 127.5;
    private static final Scalar NORMALIZE_MEAN = new Scalar(127.5, 127.5, 127.5);
    /**
     * Feather width of the person mask relative to the model input size, the
     * segmentation edge is smoothed with a small Gaussian kernel so a composited
     * person does not end with an aliased hard edge. OpenCV requires a positive
     * odd kernel size
     */
    private static final float MASK_FEATHER_SCALE = 0.03f;
    private static final int MASK_FEATHER_KERNEL_SIZE =
            toOdd(Math.round(MODEL_INPUT_SIZE * MASK_FEATHER_SCALE));

    private final ILogger mLogger;
    private final Net mModelNet;
    private final String mTag;

    public PersonSegProcessor(String modelPath, ILogger logger) {
        mModelNet = Dnn.readNetFromONNX(modelPath);
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
    }

    /**
     * Compute the soft alpha mask of the person in the given bitmap.
     *
     * @param input bitmap to be segmented, never recycled by this method
     * @return the native mask Mat (CV_8U single channel) matching the input
     * dimensions, the caller owns it and must release() it
     */
    public Mat computeMask(Bitmap input) {
        int width = input.getWidth();
        int height = input.getHeight();
        // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5 behavior)
        Mat rgba = new Mat();
        Utils.bitmapToMat(input, rgba);
        Mat rgb = new Mat();
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
        rgba.release();
        // aspect distorting resize to the model input size (see MODEL_INPUT_SIZE)
        Imgproc.resize(rgb, rgb, new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                0, 0, Imgproc.INTER_LINEAR);
        mLogger.d(mTag, "preProcess_mat:" + rgb);
        Mat blob = Dnn.blobFromImage(rgb, NORMALIZE_SCALE_FACTOR,
                new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), NORMALIZE_MEAN,
                false, false);
        rgb.release();
        mModelNet.setInput(blob);
        Mat output = mModelNet.forward();
        blob.release();

        List<Mat> images = new ArrayList<>();
        Dnn.imagesFromBlob(output, images);
        // imagesFromBlob returns views into the output blob memory, not copies,
        // the output Mat must stay alive until the channels are split out of it
        Mat merged = images.get(0);
        List<Mat> channels = new ArrayList<>();
        Core.split(merged, channels);
        merged.release();
        output.release();
        Mat background = channels.get(0);
        Mat person = channels.get(1);
        background.release();
        channels.clear();

        // the person probability channel is the alpha mask directly, the two
        // output channels are already softmax probabilities (sum to 1.0)
        Mat mask = new Mat();
        person.convertTo(mask, CvType.CV_8U, 255.0, 0.0);
        person.release();
        mLogger.d(mTag, "postProcess_mask:" + mask);
        // feather the segmentation edge, sigma 0 lets OpenCV derive it from the kernel
        Imgproc.GaussianBlur(mask, mask,
                new Size(MASK_FEATHER_KERNEL_SIZE, MASK_FEATHER_KERNEL_SIZE), 0);
        // upsample the mask back to the input bitmap resolution, keep the soft
        // values (no nearest neighbor and no argmax hard mask)
        Imgproc.resize(mask, mask, new Size(width, height), 0, 0, Imgproc.INTER_LINEAR);
        return mask;
    }

    /**
     * Force the given kernel size to a positive odd value, OpenCV requires
     * a positive odd kernel size
     */
    private static int toOdd(int kernelSize) {
        int size = Math.max(1, kernelSize);
        if (size % 2 == 0) {
            size++;
        }
        return size;
    }
}
