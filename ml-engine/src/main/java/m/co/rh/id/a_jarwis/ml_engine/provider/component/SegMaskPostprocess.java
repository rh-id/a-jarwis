package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Shared tail of the segmentation-mask postprocess, in the fixed per-mode
 * order: PERSON mode runs feather + upsample ({@link #featherAndUpsample}),
 * SUBJECT mode runs the interior hardening first
 * ({@link #hardenInterior} + feather + upsample — the ISNet salience maps
 * need their translucent interior structure re-solidified before the
 * compositing). Both tails feather the mask at the MODEL resolution, then
 * upsample it to the input bitmap resolution. Extracted from
 * {@link PersonSegProcessor} so every segmentation processor produces the
 * same soft-edge look regardless of its model input size.
 * <p>
 * The feather width is a fixed RATIO of the model input size
 * ({@link #FEATHER_SCALE_RATIO}), not a fixed pixel count: the segmentation
 * edge softness lives at the model grid resolution, so scaling the kernel
 * with the model input keeps the final — upsampled — feather thickness
 * visually identical across model resolutions (6 px at the 192 px
 * PP-HumanSeg input ≈ 31 px at the 1024 px ISNet input, both ending at ~3%
 * of the model frame after the upsample).
 */
final class SegMaskPostprocess {

    /**
     * Feather width of the segmentation mask relative to the model input
     * size, the segmentation edge is smoothed with a small Gaussian kernel
     * so a composited subject does not end with an aliased hard edge.
     * OpenCV requires a positive odd kernel size
     */
    private static final float FEATHER_SCALE_RATIO = 0.03f;
    /**
     * Erosion RADIUS of the interior hardening relative to the model input
     * size: the validated offline prototype is ImageMagick
     * {@code -morphology Erode Disk:24} on the 1024 px ISNet grid, so the
     * ratio is 24/1024 and the elliptical kernel must be built as the
     * 2*radius+1 DIAMETER ({@code k = 2 * round(modelInputSize *
     * HARDEN_ERODE_RADIUS_RATIO) + 1} = 49 at the 1024 input) — a plain
     * ratio of the model input size would yield a 25 kernel (radius 12),
     * HALF the validated radius, and the interior holes would survive
     */
    private static final float HARDEN_ERODE_RADIUS_RATIO = 24f / 1024f;
    /**
     * Gaussian sigma of the interior hardening relative to the model input
     * size: the validated offline prototype is ImageMagick
     * {@code -blur 0x12} (sigma 12) on the 1024 px ISNet grid, re-softening
     * the eroded plateau into a smooth profile before the per-pixel max
     */
    private static final float HARDEN_BLUR_SIGMA_RATIO = 12f / 1024f;

    private SegMaskPostprocess() {
    }

    /**
     * Feather the given mask at its model resolution and upsample it to the
     * target dimensions, in the fixed order of the house segmentation
     * processors (feather FIRST so the soft edge interpolates with the
     * upsample instead of being smoothed away by it).
     * <p>
     * The mask is feathered and upsampled IN PLACE: both OpenCV calls take
     * the caller's mask as their source AND destination, no other Mat is
     * produced and nothing is handed back — the caller keeps the single
     * ownership of the Mat it passed in.
     *
     * @param mask           the raw CV_8U mask at the model resolution,
     *                       feathered and upsampled IN PLACE
     * @param modelInputSize the model input size the mask was produced at
     *                       (the feather kernel is this * 0.03, odd)
     * @param targetWidth    the input bitmap width to upsample to
     * @param targetHeight   the input bitmap height to upsample to
     */
    static void featherAndUpsample(Mat mask, int modelInputSize,
                                   int targetWidth, int targetHeight) {
        int kernelSize = toOdd(Math.round(modelInputSize * FEATHER_SCALE_RATIO));
        // feather the segmentation edge, sigma 0 lets OpenCV derive it from
        // the kernel
        Imgproc.GaussianBlur(mask, mask,
                new Size(kernelSize, kernelSize), 0);
        // upsample the mask back to the input bitmap resolution, keep the
        // soft values (no nearest neighbor and no argmax hard mask)
        Imgproc.resize(mask, mask, new Size(targetWidth, targetHeight),
                0, 0, Imgproc.INTER_LINEAR);
    }

    /**
     * Harden the interior of the given SUBJECT mask at its model resolution,
     * in place: the ISNet salience maps of fabric-textured subjects (a white
     * shirt against a white wall) carry translucent interior structure —
     * fold-level dips of the raw salience that the min-max normalization
     * turns into semi-transparent holes, so the composited subject shows
     * the old background bleeding through its middle. The validated offline
     * prototype is the ImageMagick {@code -morphology Erode Disk:24} +
     * {@code -blur 0x12} pair on the 1024 px model grid, reproduced here in
     * four steps:
     * <ol>
     * <li>hard binary of the normalized mask
     * ({@code threshold 127 -> 255}) — everything at least half-opaque
     * counts as subject plateau;</li>
     * <li>erode it with an elliptical kernel of the
     * 2*radius+1 diameter {@code k = 2 * round(modelInputSize *
     * HARDEN_ERODE_RADIUS_RATIO) + 1} (= 49 at the 1024 input — radius 24,
     * the validated {@code Disk:24}; CRITICAL: the kernel is the doubled
     * ratio, a plain ratio of the model input size would halve the
     * validated radius). Eroded with OpenCV's DEFAULT border value
     * ({@code morphologyDefaultBorderValue}, +infinity): the frame border
     * counts as foreground, so a subject cropped at the frame edge is not
     * eroded away from the border side;</li>
     * <li>Gaussian blur with sigma {@code modelInputSize *
     * HARDEN_BLUR_SIGMA_RATIO} (= 12 at the 1024 input, the prototype's
     * {@code blur 0x12}) — the eroded binary grows a smooth profile back
     * towards its boundary;</li>
     * <li>per-pixel {@code max} of the original mask and the hardened one
     * (the lighten-compose): the alpha only ever RISES. The max of two
     * profiles that are non-decreasing towards the interior is itself
     * monotone, so no artificial ring forms, and the feather reach of the
     * following {@link #featherAndUpsample} stays its small ±~15 model-px
     * kernel (3% of the input) — the interior plateaus survive.</li>
     * </ol>
     * <p>
     * ACCEPTED TRADE-OFFS (intended bias of a background-removal feature):
     * genuinely translucent subjects (glass, a veil) are forced more opaque
     * than they are, and small enclosed interior holes below about the
     * twice-blur-sigma reach can be partially filled — an inaccurate hole
     * reads as a worse artifact than an opaque fold.
     * <p>
     * The mask is hardened IN PLACE: the temporary Mats are released on
     * every path and nothing is handed back — the caller keeps the single
     * ownership of the Mat it passed in.
     *
     * @param mask           the CV_8U 0..255 normalized mask at the model
     *                       resolution, hardened IN PLACE
     * @param modelInputSize the model input size the mask was produced at
     *                       (the erode kernel and the blur sigma scale with
     *                       it, see {@link #HARDEN_ERODE_RADIUS_RATIO}
     *                       and {@link #HARDEN_BLUR_SIGMA_RATIO})
     */
    static void hardenInterior(Mat mask, int modelInputSize) {
        Mat hardened = null;
        Mat element = null;
        try {
            // step 1: the hard binary of the normalized mask, the
            // half-opaque cut of the plateau
            hardened = new Mat();
            Imgproc.threshold(mask, hardened, 127, 255,
                    Imgproc.THRESH_BINARY);
            // step 2: erode the plateau with the validated radius (the
            // 2*r+1 kernel conversion, see HARDEN_ERODE_RADIUS_RATIO);
            // NO explicit border value here on purpose — the 3-arg erode
            // defaults to morphologyDefaultBorderValue (+infinity), so
            // subjects cropped at the frame edge are not eaten from the
            // border side
            int erodeRadius =
                    Math.round(modelInputSize * HARDEN_ERODE_RADIUS_RATIO);
            int kernelSize = 2 * erodeRadius + 1;
            element = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                    new Size(kernelSize, kernelSize));
            Imgproc.erode(hardened, hardened, element);
            // step 3: grow the smooth profile back towards the eroded
            // boundary (a zero kernel size lets the sigma define the
            // kernel, the featherAndUpsample comment convention)
            double blurSigma = modelInputSize * HARDEN_BLUR_SIGMA_RATIO;
            Imgproc.GaussianBlur(hardened, hardened,
                    new Size(0, 0), blurSigma);
            // step 4: lighten-compose onto the caller's mask — the alpha
            // only rises (see the monotone-max rationale in the javadoc)
            Core.max(mask, hardened, mask);
        } finally {
            // house Mat-ownership rules: every temporary Mat released on
            // every path, including the failure paths
            if (hardened != null) {
                hardened.release();
            }
            if (element != null) {
                element.release();
            }
        }
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
