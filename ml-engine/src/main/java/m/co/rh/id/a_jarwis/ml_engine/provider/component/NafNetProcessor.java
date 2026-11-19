package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.ProgressListener;
import m.co.rh.id.alogger.ILogger;

/**
 * Processor of the NAFNet photo-restoration (deblur/denoise) model, the
 * official OpenCV export from huggingface.co/opencv/deblurring_nafnet
 * (dual MIT megvii-model + Apache-2.0 BasicSR Authors), dynamic input
 * shape (any HxW). Contract: RGB in (COLOR_RGBA2RGB, blobFromImage
 * swapRB=false — the demo's swap only compensates its BGR imread), 1/255,
 * zero mean; output back-converted with the saturating alpha of 255, NO
 * channel swap before matToBitmap.
 * <p>
 * TEARDOWN POISON: destroying a forwarded net poisons every LATER net
 * in-process (the next instance's first forward SEGVs, measured on OpenCV
 * 5.0.0.1 + 5.1.0) — app-lifetime processor, evicted only by
 * {@link MLEngineInstance} dispose; same-instance forwards are safe.
 * <p>
 * Forward RSS scales at ~8.3 KB per input pixel — never run native-
 * resolution single-shot (lmkd death), hence the mandatory cap of
 * {@link #process(Bitmap, int)} and the tiles of
 * {@link #processTiled(Bitmap, int, int, ProgressListener)}.
 * 64-bit only (32-bit SIGSEGVs in TBB); a released instance is dead.
 */
class NafNetProcessor {
    private static final String TAG = "NafNetProcessor";
    /**
     * Hard ceiling of the model input side, caller values are clamped here
     */
    private static final int MAX_WORKING_SIDE = 1024;
    /**
     * The official example's input scaling: RGB 0..255 -&gt; 0..1, zero mean
     */
    private static final double PIXEL_SCALE = 1.0 / 255.0;
    /**
     * The model emits 0..1 floats: this alpha is needed or pixels clamp to 0/1
     */
    private static final double OUTPUT_ALPHA_SCALE = 255.0;
    /**
     * Zero mean of the model input (see PIXEL_SCALE)
     */
    private static final Scalar ZERO_MEAN = new Scalar(0, 0, 0);
    /**
     * One GiB, unit of the {@link #suggestedWorkingCap(Context)} thresholds
     */
    private static final long GIB = 1024L * 1024L * 1024L;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public NafNetProcessor(String modelPath, ILogger logger) {
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
        Net net;
        try {
            net = Dnn.readNetFromONNX(modelPath);
            if (net.empty()) {
                // readNetFromONNX does not reliably throw on an unparseable
                // graph, an empty net is its silent failure form
                net = null;
            }
        } catch (Exception e) {
            // the load failure is a documented finding, it must surface as
            // the IllegalStateException contract, not as a raw OpenCV
            // exception escaping the constructor
            mLogger.e(TAG, "Failed to load " + mTag
                    + " with the default DNN engine", e);
            net = null;
        }
        if (net == null) {
            throw new IllegalStateException("Failed to load " + mTag
                    + " with the default DNN engine");
        }
        mModelNet = net;
    }

    /**
     * Release the native net (weights plus the dnn workspace OpenCV retains
     * after forward()); the finalizer deletes the peer once this reference
     * drops. A released processor is dead, recreated on demand by the
     * engine cache; double release is a no-op, synchronized with the
     * process methods.
     */
    public synchronized void release() {
        if (mModelNet == null) {
            return;
        }
        // this was the last strong reference to the net wrapper
        mModelNet = null;
    }

    /**
     * A released processor holds no net anymore: fail fast instead of a
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
     * Working cap by device RAM (preview cap, tiled tile ceiling),
     * bounding the app-lifetime net's retained dnn workspace at ~8.3 KB
     * per input pixel (2.65 GB at 512).
     *
     * @param context any context
     * @return 512 on >= 8 GB RAM, 384 on >= 6 GB, 288 on >= 4 GB, 224
     * below that (also when the memory service is unavailable)
     */
    public static int suggestedWorkingCap(Context context) {
        ActivityManager activityManager = (ActivityManager) context
                .getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }
        long totalMem = memoryInfo.totalMem;
        if (totalMem >= 8L * GIB) {
            return 512;
        }
        if (totalMem >= 6L * GIB) {
            return 384;
        }
        if (totalMem >= 4L * GIB) {
            return 288;
        }
        return 224;
    }

    /**
     * Deblur the given bitmap in ONE forward at a capped working
     * resolution, the preview path: the input is downscaled (aspect
     * preserved) to at most {@code maxWorkingSide}, the result resized
     * back to the input dimensions. A memory-pressured forward throws the
     * catchable "Insufficient memory" CvException AS-IS (toast + retry).
     *
     * @param input bitmap to be deblurred, never recycled
     * @param maxWorkingSide longest-side cap, clamped to {@link #MAX_WORKING_SIDE}
     * @return the deblurred bitmap at the input's dimensions, caller owns
     */
    public synchronized Bitmap process(Bitmap input, int maxWorkingSide) {
        checkNotReleased();
        if (maxWorkingSide <= 0) {
            throw new IllegalArgumentException("maxWorkingSide must be"
                    + " positive, got " + maxWorkingSide);
        }
        // the caller's cap clamped to the hard ceiling (see MAX_WORKING_SIDE)
        int effectiveSide = Math.min(maxWorkingSide, MAX_WORKING_SIDE);
        Mat rgba = null;
        Mat rgb = null;
        Mat scaled = null;
        Mat deblurred = null;
        Mat restored = null;
        Bitmap result = null;
        try {
            // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5
            // behavior), the model consumes RGB (see the class javadoc)
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Mat working = rgb;
            int longestSide = Math.max(rgb.cols(), rgb.rows());
            if (longestSide > effectiveSide) {
                // working-cap downscale (aspect preserved, bilinear like the
                // spike): the forward happens at the capped resolution only,
                // the dims are floored at 1 so an extreme-aspect image can
                // never produce a 0-dimension Size
                double scale = effectiveSide / (double) longestSide;
                scaled = new Mat();
                Imgproc.resize(rgb, scaled,
                        new Size(Math.max(1, Math.round(rgb.cols() * scale)),
                                Math.max(1, Math.round(rgb.rows() * scale))));
                working = scaled;
            }
            deblurred = forwardRgb(working);
            // resize the deblurred working image back to the INPUT dims so
            // the result grids with the original image
            restored = new Mat();
            Imgproc.resize(deblurred, restored,
                    new Size(input.getWidth(), input.getHeight()));
            result = Bitmap.createBitmap(input.getWidth(), input.getHeight(),
                    Bitmap.Config.ARGB_8888);
            // NO channel swap here: the net output is RGB-ordered and the
            // CV_8UC3 <-&gt; ARGB_8888 matToBitmap copy preserves that order
            // (verified with the R/B-swap PSNR guard, see the class javadoc)
            Utils.matToBitmap(restored, result);
            return result;
        } catch (RuntimeException | OutOfMemoryError e) {
            // a bitmap created before a failed matToBitmap must not leak
            if (result != null) {
                result.recycle();
            }
            throw e;
        } finally {
            // every Mat is released on every path: an inference failing with
            // a native "Insufficient memory" CvException must not leak the
            // (multi-megabyte) image mats
            if (rgba != null) {
                rgba.release();
            }
            if (rgb != null) {
                rgb.release();
            }
            if (scaled != null) {
                scaled.release();
            }
            if (deblurred != null) {
                deblurred.release();
            }
            if (restored != null) {
                restored.release();
            }
        }
    }

    /**
     * Deblur the given bitmap at its FULL resolution, the save path: a
     * grid of square tiles (clamped to {@link #MAX_WORKING_SIDE}, step
     * {@code tileSize - overlap}) each forwarded at its NATIVE tile
     * resolution and merged into one full-resolution image (bounded
     * forwards, ~8.3 KB RSS per input pixel); edge tiles are padded by
     * {@link Core#BORDER_REPLICATE}, padded pixels never accumulate. The
     * merge cross-fades neighboring tiles: SUM(weight * tileOutput) /
     * SUM(weight), ramps floored at 1/(overlap+1) — 0 divides by zero.
     *
     * @param input bitmap to be deblurred, never recycled
     * @param tileSize square tile side, positive, hard-capped at
     * {@link #MAX_WORKING_SIDE}
     * @param overlap in 0..tileSize/2 (a bigger overlap makes the forward
     * count pathological)
     * @param listener per-tile progress (done, total), may be null; runs
     * on the caller's thread while the monitor is held (see
     * {@link ProgressListener})
     * @return the deblurred bitmap at the input's dimensions, caller owns
     */
    public synchronized Bitmap processTiled(Bitmap input, int tileSize,
                                            int overlap,
                                            ProgressListener listener) {
        checkNotReleased();
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be positive, got "
                    + tileSize);
        }
        // the caller's tile side clamped to the hard ceiling (see
        // MAX_WORKING_SIDE)
        int effectiveTileSize = Math.min(tileSize, MAX_WORKING_SIDE);
        if (overlap < 0 || overlap > effectiveTileSize / 2) {
            throw new IllegalArgumentException("overlap must be in"
                    + " 0..tileSize/2 (a bigger overlap shrinks the grid"
                    + " step toward 1 and makes the tile count"
                    + " pathological), got " + overlap + " for tileSize "
                    + tileSize + " (capped to " + effectiveTileSize + ")");
        }
        int width = input.getWidth();
        int height = input.getHeight();
        int step = effectiveTileSize - overlap;
        int total = ((width - 1) / step + 1) * ((height - 1) / step + 1);
        Mat rgba = null;
        Mat rgb = null;
        Mat sumWeighted = null;
        Mat sumWeight = null;
        Mat ramp = null;
        Mat ramp3 = null;
        Bitmap result = null;
        try {
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            // the accumulators: SUM(weight * tileOutput) and SUM(weight),
            // zero-initialized (the Scalar constructor zeroes every channel)
            sumWeighted = new Mat(height, width, CvType.CV_32FC3,
                    new Scalar(0, 0, 0));
            sumWeight = new Mat(height, width, CvType.CV_32FC1,
                    new Scalar(0));
            // the tile blend ramp depends only on the tile-local coordinates
            // and every tile is exactly tileSize x tileSize: built ONCE and
            // shared by the whole grid
            ramp = buildWeightRamp(effectiveTileSize, overlap);
            // cv::multiply/divide need matching channel counts on both
            // sides, the per-pixel weight is broadcast into 3 channels once
            ramp3 = new Mat();
            List<Mat> rampChannels = new ArrayList<>();
            rampChannels.add(ramp);
            rampChannels.add(ramp);
            rampChannels.add(ramp);
            Core.merge(rampChannels, ramp3);
            mLogger.i(TAG, "Tiled deblur " + width + "x" + height
                    + " tile=" + effectiveTileSize
                    + (tileSize != effectiveTileSize
                            ? " (requested " + tileSize + " capped)" : "")
                    + " overlap=" + overlap + " tiles=" + total);
            int done = 0;
            for (int tileTop = 0; tileTop < height; tileTop += step) {
                for (int tileLeft = 0; tileLeft < width; tileLeft += step) {
                    int clipWidth = Math.min(effectiveTileSize,
                            width - tileLeft);
                    int clipHeight = Math.min(effectiveTileSize,
                            height - tileTop);
                    int padRight = effectiveTileSize - clipWidth;
                    int padBottom = effectiveTileSize - clipHeight;
                    Mat tileIn = null;
                    Mat tileOut = null;
                    Mat tileOut32 = null;
                    Mat weighted = null;
                    try {
                        // the tile source: the in-image part of the grid
                        // cell, padded by edge replication to the full
                        // tileSize square when it touches the image border
                        Mat tileSrc = rgb.submat(tileTop,
                                tileTop + clipHeight, tileLeft,
                                tileLeft + clipWidth);
                        try {
                            if (padRight > 0 || padBottom > 0) {
                                mLogger.d(TAG, "Edge tile at (" + tileLeft
                                        + "," + tileTop + ") padded by right="
                                        + padRight + " bottom=" + padBottom
                                        + " via BORDER_REPLICATE");
                                tileIn = new Mat();
                                // OpenCV 5 moved copyMakeBorder and the
                                // border flags from Imgproc to Core
                                Core.copyMakeBorder(tileSrc, tileIn,
                                        0, padBottom, 0, padRight,
                                        Core.BORDER_REPLICATE);
                            } else {
                                // the clone detaches the forward input from
                                // the shared full-image Mat
                                tileIn = tileSrc.clone();
                            }
                        } finally {
                            // a submat release frees the header only, the
                            // parent rgb data stays alive
                            tileSrc.release();
                        }
                        // deblur the tile at its NATIVE resolution (never
                        // resized up, it is already <= tileSize)
                        tileOut = forwardRgb(tileIn);
                        // accumulate only the in-image region: the padded
                        // pixels are replication artifacts, they must not
                        // contribute to the output
                        tileOut32 = new Mat();
                        tileOut.convertTo(tileOut32, CvType.CV_32FC3);
                        weighted = new Mat();
                        Core.multiply(tileOut32, ramp3, weighted);
                        accumulate(sumWeighted, sumWeight, tileLeft, tileTop,
                                clipWidth, clipHeight, weighted, ramp);
                    } finally {
                        if (tileIn != null) {
                            tileIn.release();
                        }
                        if (tileOut != null) {
                            tileOut.release();
                        }
                        if (tileOut32 != null) {
                            tileOut32.release();
                        }
                        if (weighted != null) {
                            weighted.release();
                        }
                    }
                    done++;
                    if (listener != null) {
                        listener.onProgress(done, total);
                    }
                }
            }
            // normalize: output = SUM(weight * tileOutput) / SUM(weight).
            // cv::divide needs matching channel counts, the weight sum is
            // broadcast into 3 channels for the division
            Mat sumWeight3 = new Mat();
            Mat normalized = new Mat();
            Mat result8 = new Mat();
            try {
                List<Mat> sumWeightChannels = new ArrayList<>();
                sumWeightChannels.add(sumWeight);
                sumWeightChannels.add(sumWeight);
                sumWeightChannels.add(sumWeight);
                Core.merge(sumWeightChannels, sumWeight3);
                Core.divide(sumWeighted, sumWeight3, normalized);
                // the normalized average stays in the 0..255 range (a
                // weighted average of 0..255 tile outputs), converted with
                // the default saturating cast (no alpha scaling)
                normalized.convertTo(result8, CvType.CV_8U);
                result = Bitmap.createBitmap(width, height,
                        Bitmap.Config.ARGB_8888);
                // NO channel swap: same verified CV_8UC3 RGB <-&gt; ARGB_8888
                // matToBitmap order as the single-shot path
                Utils.matToBitmap(result8, result);
                return result;
            } finally {
                sumWeight3.release();
                normalized.release();
                result8.release();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // a bitmap created before a failed matToBitmap must not leak
            if (result != null) {
                result.recycle();
            }
            throw e;
        } finally {
            if (rgba != null) {
                rgba.release();
            }
            if (rgb != null) {
                rgb.release();
            }
            if (sumWeighted != null) {
                sumWeighted.release();
            }
            if (sumWeight != null) {
                sumWeight.release();
            }
            if (ramp != null) {
                ramp.release();
            }
            if (ramp3 != null) {
                ramp3.release();
            }
        }
    }

    /**
     * One forward on an RGB CV_8UC3 image: blob (1/255, zero mean,
     * swapRB=false), forward, imagesFromBlob, CV_8U conversion with the
     * alpha of 255. NEVER bulk get/put on the n-dimensional blob: the
     * OpenCV 5.0.0.1 bindings transfer zeros for n-dim Mats — pixels
     * leave the blob only via imagesFromBlob; memory-pressured forwards
     * are rethrown AS-IS (see {@link #process(Bitmap, int)}).
     *
     * @param rgb RGB CV_8UC3 input, released by the caller
     * @return the deblurred RGB CV_8UC3 image at the same dimensions
     */
    private Mat forwardRgb(Mat rgb) {
        Mat blob = null;
        Mat output = null;
        try {
            // the Mat is already RGB-ordered (COLOR_RGBA2RGB upstream) and
            // at its final size, so swapRB stays false and the size matches
            // the Mat (the dynamic-shape model accepts any HxW)
            blob = Dnn.blobFromImage(rgb, PIXEL_SCALE,
                    new Size(rgb.cols(), rgb.rows()), ZERO_MEAN, false, false);
            try {
                mModelNet.setInput(blob);
                output = mModelNet.forward();
            } catch (CvException e) {
                // logged and rethrown AS-IS (no wrapping): a native
                // "Insufficient memory" failure is the documented graceful
                // failure of this processor on 64-bit memory pressure, the
                // page catches it and offers the retry (the cached net
                // survives)
                mLogger.e(TAG, "NAFNet forward failed on " + rgb.cols() + "x"
                        + rgb.rows() + " input (memory pressure on the"
                        + " device?)", e);
                throw e;
            }
            List<Mat> images = new ArrayList<>();
            try {
                Dnn.imagesFromBlob(output, images);
                if (images.isEmpty()) {
                    throw new IllegalStateException(
                            "NAFNet forward produced no image output");
                }
                Mat image = images.get(0);
                // 0..1 floats -> 0..255, the alpha of 255 is critical (see
                // OUTPUT_ALPHA_SCALE); the in-place conversion reallocates
                // the Mat away from the shared blob memory
                image.convertTo(image, CvType.CV_8U, OUTPUT_ALPHA_SCALE);
                // detach from the blob-shared memory: the blob (and the
                // unpack views) are released by this method's finally
                return image.clone();
            } finally {
                for (Mat image : images) {
                    image.release();
                }
                images.clear();
            }
        } finally {
            if (blob != null) {
                blob.release();
            }
            if (output != null) {
                output.release();
            }
        }
    }

    /**
     * The shared per-tile blend weight Mat: per-axis edge ramps' product,
     * 1 at the center falling to 1/(overlap+1) at the edges. 2-D, bulk put
     * is safe (the zero-transfer bug is n-dim only).
     */
    private static Mat buildWeightRamp(int tileSize, int overlap) {
        Mat ramp = new Mat(tileSize, tileSize, CvType.CV_32FC1);
        float[] buffer = new float[tileSize * tileSize];
        for (int y = 0; y < tileSize; y++) {
            float rampY = edgeRamp(y, overlap) * edgeRamp(tileSize - 1 - y,
                    overlap);
            for (int x = 0; x < tileSize; x++) {
                float rampX = edgeRamp(x, overlap)
                        * edgeRamp(tileSize - 1 - x, overlap);
                buffer[y * tileSize + x] = rampX * rampY;
            }
        }
        ramp.put(0, 0, buffer);
        return ramp;
    }

    /**
     * One linear edge ramp: 1 at the center, 1/(overlap+1) AT the edge
     * pixel — 0 would divide by zero at single-covered borders.
     */
    private static float edgeRamp(int distanceToEdge, int overlap) {
        if (overlap <= 0 || distanceToEdge >= overlap) {
            return 1f;
        }
        return (distanceToEdge + 1f) / (overlap + 1f);
    }

    /**
     * Add the in-image region of one tile's weighted output into the
     * running sums (fresh destination per addition: no in-place aliasing).
     */
    private static void accumulate(Mat sumWeighted, Mat sumWeight,
                                   int tileLeft, int tileTop,
                                   int clipWidth, int clipHeight,
                                   Mat weightedTile, Mat ramp) {
        Mat weightedView = null;
        Mat rampView = null;
        Mat sumWeightedView = null;
        Mat sumWeightView = null;
        Mat weightedSum = null;
        Mat weightSum = null;
        try {
            weightedView = weightedTile.submat(0, clipHeight, 0, clipWidth);
            rampView = ramp.submat(0, clipHeight, 0, clipWidth);
            sumWeightedView = sumWeighted.submat(tileTop,
                    tileTop + clipHeight, tileLeft, tileLeft + clipWidth);
            sumWeightView = sumWeight.submat(tileTop,
                    tileTop + clipHeight, tileLeft, tileLeft + clipWidth);
            weightedSum = new Mat();
            Core.add(sumWeightedView, weightedView, weightedSum);
            weightedSum.copyTo(sumWeightedView);
            weightSum = new Mat();
            Core.add(sumWeightView, rampView, weightSum);
            weightSum.copyTo(sumWeightView);
        } finally {
            if (weightedView != null) {
                weightedView.release();
            }
            if (rampView != null) {
                rampView.release();
            }
            if (sumWeightedView != null) {
                sumWeightedView.release();
            }
            if (sumWeightView != null) {
                sumWeightView.release();
            }
            if (weightedSum != null) {
                weightedSum.release();
            }
            if (weightSum != null) {
                weightSum.release();
            }
        }
    }
}
