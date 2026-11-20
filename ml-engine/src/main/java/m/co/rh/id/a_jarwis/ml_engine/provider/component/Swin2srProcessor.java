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
 * Processor of the Swin2SR real-world 4x super-resolution model (SwinV2),
 * the onnx-community export described on {@link
 * m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType#SWIN2SR_UPSCALE}
 * (Apache-2.0, licence source there). FIXED export shape: a 64x64 input
 * tile forwards to a 256x256 output tile — the grid runs at the INPUT
 * resolution and every tile accumulates on the 4x OUTPUT canvas, so the
 * {@link TileBlend} units are canvas pixels (the per-tile weight ramp is
 * built at 256 with the overlap converted x4); edge tiles are padded by
 * BORDER_REPLICATE and the padded pixels never accumulate. The merge
 * cross-fades neighboring tiles: SUM(weight * tileOutput) / SUM(weight),
 * ramps floored at 1/(overlap+1) — the spike's UNBLENDED seam probe
 * measured 3.79/255 mean abs diff, blending is mandatory. Contract: RGB
 * in (COLOR_RGBA2RGB, blobFromImage swapRB=false), 1/255, zero mean; the
 * exported graph includes the bicubic-residual add so the output is a
 * direct image in 0..1 floats, back-converted with the saturating alpha
 * of 255, NO channel swap before matToBitmap. Peak merge memory ~47 B per
 * output pixel (~789 MB at the 1024 input cap) — hence the RAM-tiered
 * {@link #suggestedUpscaleCap(Context)} and the hard
 * {@link #MAX_UPSCALE_INPUT_SIDE} ceiling.
 * <p>
 * TEARDOWN POISON: destroying a forwarded net poisons every LATER net
 * in-process (see {@link NafNetProcessor}) — app-lifetime processor,
 * evicted only by {@link MLEngineInstance} dispose; same-instance
 * forwards are safe; the NAFNet cold-first-forward SEGV law never
 * reproduced on this net (its FIXED 64x64 shape forwards cold safely —
 * the suite's first forward is a cold 64x64 and green). 64-bit only
 * (32-bit SIGSEGVs in TBB); a released instance is dead.
 */
class Swin2srProcessor {
    private static final String TAG = "Swin2srProcessor";
    /**
     * The model's FIXED input tile side, pinned by the export (the -64 in
     * the repo name)
     */
    private static final int TILE_IN = 64;
    /**
     * The model's FIXED output tile side, {@link #TILE_IN} x 4
     */
    private static final int TILE_OUT = TILE_IN * 4;
    /**
     * Hard ceiling of the input longest side: an uncapped multi-MP input
     * would allocate a ~3 GB merge canvas at 256 B per input pixel
     */
    private static final int MAX_UPSCALE_INPUT_SIDE = 1024;
    /**
     * The model's input scaling: RGB 0..255 -&gt; 0..1, zero mean
     * (rgb_div255 per the phase-0 spike)
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
     * One GiB, unit of the {@link #suggestedUpscaleCap(Context)} thresholds
     */
    private static final long GIB = 1024L * 1024L * 1024L;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public Swin2srProcessor(String modelPath, ILogger logger) {
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
     * Input cap by device RAM, bounding the app-lifetime net's peak merge
     * memory at ~47 B per output pixel (16 CV_32F accumulators plus the
     * finalize intermediates plus 4 B/px output bitmap; one input pixel
     * produces 16 output pixels at 4x): ~789/444/197/111 MB square worst
     * case at the tiers.
     *
     * @param context any context
     * @return 1024 on >= 8 GB RAM, 768 on >= 6 GB, 512 on >= 4 GB, 384
     * below that (also when the memory service is unavailable)
     */
    public static int suggestedUpscaleCap(Context context) {
        ActivityManager activityManager = (ActivityManager) context
                .getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }
        long totalMem = memoryInfo.totalMem;
        if (totalMem >= 8L * GIB) {
            return 1024;
        }
        if (totalMem >= 6L * GIB) {
            return 768;
        }
        if (totalMem >= 4L * GIB) {
            return 512;
        }
        return 384;
    }

    /**
     * Upscale the given bitmap from a capped working copy, the preview
     * path: the input is downscaled (aspect preserved) to at most
     * {@code maxWorkingSide} (also clamped to
     * {@link #MAX_UPSCALE_INPUT_SIDE}), tiled-upscaled 4x and the 4x
     * canvas resized back to the INPUT dimensions so the result grids
     * with the original image. A memory-pressured forward throws the
     * catchable "Insufficient memory" CvException AS-IS (toast + retry).
     *
     * @param input bitmap to be upscaled, never recycled
     * @param maxWorkingSide longest-side cap, clamped to
     * {@link #MAX_UPSCALE_INPUT_SIDE}
     * @param listener per-tile progress (done, total), may be null; runs
     * on the caller's thread while the monitor is held (see
     * {@link ProgressListener})
     * @return the upscaled bitmap at the input's dimensions, caller owns
     */
    public synchronized Bitmap process(Bitmap input, int maxWorkingSide,
                                       ProgressListener listener) {
        checkNotReleased();
        if (maxWorkingSide <= 0) {
            throw new IllegalArgumentException("maxWorkingSide must be"
                    + " positive, got " + maxWorkingSide);
        }
        // the caller's cap clamped to the hard ceiling (see
        // MAX_UPSCALE_INPUT_SIDE)
        int effectiveSide = Math.min(maxWorkingSide, MAX_UPSCALE_INPUT_SIDE);
        Mat rgba = null;
        Mat rgb = null;
        Mat scaled = null;
        Mat canvas = null;
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
                // working-cap downscale (aspect preserved, bilinear like
                // the spike): the forward grid happens at the capped
                // resolution only, the dims are floored at 1 so an
                // extreme-aspect image can never produce a 0-dimension Size
                double scale = effectiveSide / (double) longestSide;
                scaled = new Mat();
                Imgproc.resize(rgb, scaled,
                        new Size(Math.max(1, Math.round(rgb.cols() * scale)),
                                Math.max(1, Math.round(rgb.rows() * scale))));
                working = scaled;
            }
            // a quarter-tile cross-fade: fixed overlap of the preview
            // path (the save path's overlap is the caller's choice)
            canvas = upscaleCanvas(working, working.cols(), working.rows(),
                    TILE_IN / 4, listener);
            // resize the upscaled working canvas back to the INPUT dims so
            // the result grids with the original image
            restored = new Mat();
            Imgproc.resize(canvas, restored,
                    new Size(input.getWidth(), input.getHeight()));
            result = Bitmap.createBitmap(input.getWidth(), input.getHeight(),
                    Bitmap.Config.ARGB_8888);
            // NO channel swap here: the net output is RGB-ordered and the
            // CV_8UC3 &lt;-&gt; ARGB_8888 matToBitmap copy preserves that order
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
            if (canvas != null) {
                canvas.release();
            }
            if (restored != null) {
                restored.release();
            }
        }
    }

    /**
     * Upscale the given bitmap at its FULL resolution, the save path: a
     * grid of {@link #TILE_IN} square input tiles (step
     * {@code TILE_IN - overlap}) each forwarded at the FIXED 64x64 export
     * shape and merged into one 4x canvas (bounded forwards, ~47 B merge
     * memory per output pixel); edge tiles are padded by
     * {@link Core#BORDER_REPLICATE}, padded pixels never accumulate (see
     * the class javadoc for the blend). The input longest side is
     * HARD-capped, callers pre-cap with
     * {@link #suggestedUpscaleCap(Context)}.
     *
     * @param input bitmap to be upscaled, never recycled, longest side
     * &lt;= {@link #MAX_UPSCALE_INPUT_SIDE}
     * @param overlap in 0..{@link #TILE_IN}/2, INPUT pixels (a bigger
     * overlap makes the forward count pathological)
     * @param listener per-tile progress (done, total), may be null; runs
     * on the caller's thread while the monitor is held (see
     * {@link ProgressListener})
     * @return the upscaled bitmap at 4x the input's dimensions, caller owns
     */
    public synchronized Bitmap processTiled(Bitmap input, int overlap,
                                            ProgressListener listener) {
        checkNotReleased();
        if (overlap < 0 || overlap > TILE_IN / 2) {
            throw new IllegalArgumentException("overlap must be in"
                    + " 0..tileSize/2 (a bigger overlap shrinks the grid"
                    + " step toward 1 and makes the tile count"
                    + " pathological), got " + overlap + " for tileSize "
                    + TILE_IN);
        }
        int width = input.getWidth();
        int height = input.getHeight();
        if (Math.max(width, height) > MAX_UPSCALE_INPUT_SIDE) {
            throw new IllegalArgumentException("the input must be pre-capped"
                    + " to at most " + MAX_UPSCALE_INPUT_SIDE + " on the"
                    + " longest side (see suggestedUpscaleCap), got "
                    + width + "x" + height);
        }
        Mat rgba = null;
        Mat rgb = null;
        Mat canvas = null;
        Bitmap result = null;
        try {
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            // the merge runs at the input's own dims, its canvas is 4x
            canvas = upscaleCanvas(rgb, width, height, overlap, listener);
            result = Bitmap.createBitmap(canvas.cols(), canvas.rows(),
                    Bitmap.Config.ARGB_8888);
            // NO channel swap: same verified CV_8UC3 RGB &lt;-&gt; ARGB_8888
            // matToBitmap order as the preview path
            Utils.matToBitmap(canvas, result);
            return result;
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
            if (canvas != null) {
                canvas.release();
            }
        }
    }

    /**
     * The shared tiled merge behind {@link #process(Bitmap, int,
     * ProgressListener)} and {@link #processTiled(Bitmap, int,
     * ProgressListener)}: forwards a grid of 64x64 input tiles (step
     * {@code TILE_IN - overlap}) and blends every 256x256 output tile into
     * the 4x canvas (see the class javadoc). The returned canvas Mat is
     * caller owned, all intermediates are released on every path.
     *
     * @param rgb RGB CV_8UC3 source image at width x height, released by
     * the caller
     * @param width source width, INPUT pixels
     * @param height source height, INPUT pixels
     * @param overlap tile overlap, INPUT pixels, in 0..{@link #TILE_IN}/2
     * @param listener per-tile progress, may be null, caller's thread
     * @return the merged CV_8UC3 canvas at 4x the source dimensions
     */
    private Mat upscaleCanvas(Mat rgb, int width, int height, int overlap,
                              ProgressListener listener) {
        // the forward grid steps at the INPUT resolution, all the blend
        // math happens on the 4x canvas (TileBlend UNITS CONTRACT)
        int outScale = TILE_OUT / TILE_IN;
        int step = TILE_IN - overlap;
        int total = ((width - 1) / step + 1) * ((height - 1) / step + 1);
        Mat sumWeighted = null;
        Mat sumWeight = null;
        Mat ramp = null;
        Mat ramp3 = null;
        Mat canvas = null;
        try {
            // the accumulators: SUM(weight * tileOutput) and SUM(weight),
            // zero-initialized on the 4x OUTPUT canvas (the Scalar
            // constructor zeroes every channel)
            sumWeighted = new Mat(height * outScale, width * outScale,
                    CvType.CV_32FC3, new Scalar(0, 0, 0));
            sumWeight = new Mat(height * outScale, width * outScale,
                    CvType.CV_32FC1, new Scalar(0));
            // the tile blend ramp depends only on the tile-local
            // coordinates and every tile is exactly TILE_OUT x TILE_OUT on
            // the canvas: built ONCE at the OUTPUT tile size with the
            // OUTPUT overlap (overlap x4) and shared by the whole grid
            ramp = TileBlend.buildWeightRamp(TILE_OUT, overlap * outScale);
            // cv::multiply/divide need matching channel counts on both
            // sides, the per-pixel weight is broadcast into 3 channels once
            ramp3 = new Mat();
            List<Mat> rampChannels = new ArrayList<>();
            rampChannels.add(ramp);
            rampChannels.add(ramp);
            rampChannels.add(ramp);
            Core.merge(rampChannels, ramp3);
            mLogger.i(TAG, "Tiled upscale " + width + "x" + height
                    + " tile=" + TILE_IN + " overlap=" + overlap
                    + " tiles=" + total);
            int done = 0;
            for (int tileTop = 0; tileTop < height; tileTop += step) {
                for (int tileLeft = 0; tileLeft < width; tileLeft += step) {
                    int clipWidth = Math.min(TILE_IN, width - tileLeft);
                    int clipHeight = Math.min(TILE_IN, height - tileTop);
                    int padRight = TILE_IN - clipWidth;
                    int padBottom = TILE_IN - clipHeight;
                    Mat tileIn = null;
                    Mat tileOut = null;
                    Mat tileOut32 = null;
                    Mat weighted = null;
                    try {
                        // the tile source: the in-image part of the grid
                        // cell, padded by edge replication to the full
                        // 64x64 export shape when it touches the border
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
                        // upscale the tile at the FIXED export shape
                        // (never resized, the grid runs at the input
                        // resolution)
                        tileOut = forwardRgb(tileIn);
                        // accumulate only the in-image region of the
                        // 256x256 tile: the padded pixels are replication
                        // artifacts, they must not contribute to the
                        // output — every coordinate and clip dim converts
                        // x4 into the canvas space
                        tileOut32 = new Mat();
                        tileOut.convertTo(tileOut32, CvType.CV_32FC3);
                        weighted = new Mat();
                        Core.multiply(tileOut32, ramp3, weighted);
                        TileBlend.accumulate(sumWeighted, sumWeight,
                                tileLeft * outScale, tileTop * outScale,
                                clipWidth * outScale,
                                clipHeight * outScale, weighted, ramp);
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
                canvas = new Mat();
                normalized.convertTo(canvas, CvType.CV_8U);
                return canvas;
            } finally {
                sumWeight3.release();
                normalized.release();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // a canvas allocated before a failed step must not leak (the
            // caller never sees it on a throw)
            if (canvas != null) {
                canvas.release();
            }
            throw e;
        } finally {
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
     * One forward on an RGB CV_8UC3 64x64 tile: blob (1/255, zero mean,
     * swapRB=false), forward, imagesFromBlob, CV_8U conversion with the
     * alpha of 255. NEVER bulk get/put on the n-dimensional blob: the
     * OpenCV 5.0.0.1 bindings transfer zeros for n-dim Mats — pixels
     * leave the blob only via imagesFromBlob; memory-pressured forwards
     * are rethrown AS-IS (see {@link #process(Bitmap, int,
     * ProgressListener)}).
     *
     * @param rgb RGB CV_8UC3 64x64 input tile, released by the caller
     * @return the upscaled RGB CV_8UC3 256x256 output tile
     */
    private Mat forwardRgb(Mat rgb) {
        Mat blob = null;
        Mat output = null;
        try {
            // the Mat is already RGB-ordered (COLOR_RGBA2RGB upstream) and
            // exactly the FIXED export shape, so swapRB stays false and
            // the size pins the model's input
            blob = Dnn.blobFromImage(rgb, PIXEL_SCALE,
                    new Size(TILE_IN, TILE_IN), ZERO_MEAN, false, false);
            try {
                mModelNet.setInput(blob);
                output = mModelNet.forward();
            } catch (CvException e) {
                // logged and rethrown AS-IS (no wrapping): a native
                // "Insufficient memory" failure is the documented graceful
                // failure of this processor on 64-bit memory pressure, the
                // page catches it and offers the retry (the cached net
                // survives)
                mLogger.e(TAG, "Swin2SR forward failed on " + rgb.cols() + "x"
                        + rgb.rows() + " tile (memory pressure on the"
                        + " device?)", e);
                throw e;
            }
            List<Mat> images = new ArrayList<>();
            try {
                Dnn.imagesFromBlob(output, images);
                if (images.isEmpty()) {
                    throw new IllegalStateException(
                            "Swin2SR forward produced no image output");
                }
                Mat image = images.get(0);
                // 0..1 floats -> 0..255, the alpha of 255 is critical (see
                // OUTPUT_ALPHA_SCALE); the in-place conversion reallocates
                // the Mat away from the shared blob memory. The exported
                // graph includes the bicubic-residual add, the tile comes
                // out as a DIRECT image — an export without the residual
                // would surface as a near-black output here (the test
                // suite's bicubic guard fails on exactly that)
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
}
