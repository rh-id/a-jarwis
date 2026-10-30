package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.geometry.Geometry;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import m.co.rh.id.a_jarwis.ml_engine.model.CancelledCheck;
import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.StrokeBatch;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Magic eraser engine: removes user-marked objects from a photo.
 * The user paints brush strokes over the objects, the strokes are grouped into
 * their connected painted components and each group gets its own EfficientSAM
 * prompt (a bounding box plus a few foreground points, see
 * {@link #convertStrokesToPrompts}) — SAM segments one object per prompt set —
 * the per-group masks are unioned, and LaMa inpaints the masked regions so the
 * background fills the holes.
 * <p>
 * The LaMa inpainting runs on crops around the mask (the model inpaints the
 * largest hole of its 512x512 input, so a full image input would make any
 * mask look small): the mask bounding box is expanded by half its size on
 * each side, and when the expanded region still covers most of the image the
 * mask is split into its connected components and each component is inpainted
 * in its own crop, falling back to a whole-image downscale for a single huge
 * component.
 * <p>
 * The engine is stateless regarding masks, every returned mask and bitmap is
 * caller owned (released/recycled by the caller). The processing is a heavy
 * native operation, callers are expected to run it serialized (e.g. on a
 * single background executor).
 */
public class MagicEraserEngine {
    /**
     * EfficientSAM model input resolution (see
     * {@link EfficientSamProcessor}), the prompt coordinates must be scaled
     * into this space with the same aspect distorting resize the image gets.
     * The exported reference 1024 size: the temporary 512 experiment was
     * reverted after it measured NO retained-footprint improvement and
     * degraded mask quality for smaller objects (see the processor class
     * javadoc). Package-private so the instrumented tests derive the
     * model-space expectations from it instead of hardcoding literals.
     */
    static final int SAM_MODEL_INPUT_SIZE = 1024;
    /**
     * Prompt label marking a box top-left corner (see the EfficientSAM
     * processor model contract)
     */
    public static final float PROMPT_LABEL_BOX_TOP_LEFT = 2f;
    /**
     * Prompt label marking a box bottom-right corner (see the EfficientSAM
     * processor model contract)
     */
    public static final float PROMPT_LABEL_BOX_BOTTOM_RIGHT = 3f;
    /**
     * Prompt label marking a foreground point (see the EfficientSAM processor
     * model contract)
     */
    public static final float PROMPT_LABEL_FOREGROUND = 1f;
    /**
     * Maximum number of prompt entries the EfficientSAM model accepts
     * (2 box corners + up to 4 sampled foreground points)
     */
    private static final int MAX_PROMPT_ENTRIES = 6;
    /**
     * Maximum number of foreground points sampled along the stroke paths,
     * the remaining prompt slots after the 2 box entries
     */
    private static final int MAX_SAMPLED_POINTS = MAX_PROMPT_ENTRIES - 2;
    /**
     * A mask (fully opaque pixel sum) covering less than this fraction of the
     * image area is treated as "no object detected", it absorbs false positives
     * of the model that segment only a few scattered regions
     * (same threshold as the person detection of {@link PersonSegEngine})
     */
    private static final double MIN_MASK_AREA_RATIO = 0.002;
    /**
     * Binary threshold separating the mask from the background, the SAM mask
     * is feathered (soft edge values in between) and LaMa requires a hard
     * 0/255 binary mask (it binarizes the resized mask itself)
     */
    private static final int MASK_BINARY_THRESHOLD = 127;
    /**
     * Mask pixel value for the masked (inside) region
     */
    private static final int MASK_VALUE = 255;
    /**
     * Fraction of the mask bounding box size added as context padding on
     * each side of the LaMa crop, so the model sees enough surroundings to
     * synthesize a plausible fill
     */
    private static final float REGION_EXPAND_SCALE = 0.5f;
    /**
     * When the expanded mask bounding box covers more than this fraction of
     * the image area, the mask is split into its connected components and
     * each component is inpainted in its own (smaller) crop
     */
    private static final double MAX_REGION_AREA_RATIO = 0.6;
    /**
     * Upper bound of the connected components processed per erase, a mask
     * with more disjoint parts is pathological and inpainting each part
     * separately would dominate the runtime
     */
    private static final int MAX_COMPONENTS = 8;
    /**
     * Feather width of the paste-back mask relative to the region min
     * dimension: the inpainted crop is blended into the image only over the
     * masked pixels with a softened edge so the patch boundary is not visible.
     * OpenCV requires a positive odd kernel size
     */
    private static final float PASTE_FEATHER_SCALE = 0.03f;
    /**
     * Brush stroke thickness relative to the image min dimension per brush
     * size step (see {@link EditorStroke} size 1..5): size 1 covers ~1% of
     * the min dimension, size 5 ~5%
     */
    private static final float BRUSH_THICKNESS_SCALE = 0.01f;

    /**
     * Feather width of the SAM mask relative to the actual raw mask plane
     * side (the export's fixed 1024² output plane, see
     * {@link EfficientSamProcessor}), the binary
     * segmentation edge of the unioned group masks is smoothed with a small
     * Gaussian kernel so the mask overlay display and the erase paste-back do
     * not end with an aliased hard edge. Package-private so the instrumented
     * tests derive the kernel through {@link #samMaskFeatherKernel(int)}
     * instead of hardcoding it.
     */
    private static final float SAM_MASK_FEATHER_SCALE = 0.03f;

    /**
     * Feather kernel for a raw SAM mask of the given plane side: 3% of the
     * side, forced to a positive odd size (OpenCV requires a positive odd
     * kernel). Derived from the ACTUAL mask dimension — 1024² raw masks give
     * 31, a hypothetical 512² output would give 15.
     */
    static int samMaskFeatherKernel(int maskSide) {
        return toOdd(Math.round(maskSide * SAM_MASK_FEATHER_SCALE));
    }
    /**
     * Upper bound of the prompt groups processed per computeObjectMask call,
     * one SAM run per group (a pathological marking with many disconnected
     * taps would otherwise run SAM per tap)
     */
    private static final int MAX_MASK_GROUPS = 8;
    /**
     * Display-only opacity of the mask tint overlay: the overlay bitmap is
     * built from the mask values scaled by this factor so the photo stays
     * visible under the detected region. The stored {@link MagicEraserMask}
     * keeps the full values — the erase hard-thresholds at 127 — only the
     * display copy of the conversion is scaled
     */
    private static final float OVERLAY_TINT_ALPHA_SCALE = 0.5f;
    /**
     * Compile-time gate of the spike diagnostics (see {@link #spikeLog()}):
     * false in committed code, flipped to true only temporarily for a
     * connectedCheck spike run
     */
    private static final boolean SPIKE_LOG = false;

    private final ProviderValue<MLEngineInstance> mMLEngine;

    public MagicEraserEngine(Provider provider) {
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    /**
     * Ready-to-use EfficientSAM prompt: the point coordinates are already
     * scaled into the model input space (see {@link #SAM_MODEL_INPUT_SIZE}),
     * the unused trailing entries are
     * padded with coordinates 0,0 and label -1 (see the EfficientSAM
     * processor model contract)
     */
    public static final class Prompts {
        /**
         * [N][2] prompt coordinates (x,y) in the model input space (see
         * {@link #SAM_MODEL_INPUT_SIZE})
         */
        private final float[][] mPoints;
        /**
         * Prompt label per point, same length as mPoints
         */
        private final float[] mLabels;

        private Prompts(float[][] points, float[] labels) {
            mPoints = points;
            mLabels = labels;
        }

        public float[][] getPoints() {
            return mPoints;
        }

        public float[] getLabels() {
            return mLabels;
        }
    }

    /**
     * Result of the erase pipeline ({@link #eraseObject}): the erased bitmap
     * or the no-object marker when the segmentation found nothing to erase
     */
    public static final class EraseObjectResult {
        /**
         * The erased bitmap, null when nothing was erased (no object
         * detected), the caller owns it and must recycle() it
         */
        private final Bitmap mResult;
        /**
         * true when the segmentation found no object, the erase is skipped
         * with a note
         */
        private final boolean mNoObject;

        private EraseObjectResult(Bitmap result, boolean noObject) {
            mResult = result;
            mNoObject = noObject;
        }

        public Bitmap getResult() {
            return mResult;
        }

        public boolean isNoObject() {
            return mNoObject;
        }
    }

    /**
     * Convert the user strokes into the EfficientSAM prompt: the union
     * bounding box of all strokes becomes the 2 box entries (labels
     * 2 = top-left, 3 = bottom-right) and up to 4 foreground points (label 1)
     * are sampled evenly along the stroke paths, distributed proportionally
     * to each stroke's point count (duplicates skipped). The total is capped
     * at {@link #MAX_PROMPT_ENTRIES}: the box entries come first, sampled
     * points are dropped when the cap is reached. Unused trailing entries are
     * padded with coordinates 0,0 and label -1.
     * <p>
     * A degenerate marking (single taps or a straight line whose union
     * bounding box has zero extent) cannot carry a box prompt: it gets a
     * points-only prompt set — the marked points as foreground (label 1),
     * the box slots padded with label -1 (coords 0,0) — SAM point prompts
     * are first-class and need no box.
     * <p>
     * The stroke points are normalized (nx = px / width), and the model's
     * aspect distorting INPUT resize maps a pixel px to px * SAM_MODEL_INPUT_SIZE
     * / width, so a normalized point maps into the model input space
     * directly as nx * SAM_MODEL_INPUT_SIZE (the image dimensions are not
     * involved, dividing by them again would double-scale the prompt into a
     * degenerate corner box). Prompts live in the INPUT space only: the
     * masks come back at the export's fixed 1024² OUTPUT plane (see
     * {@link EfficientSamProcessor}), every downstream dimension (feather
     * kernel, hasMask area, upsample) derives from the actual mask Mat, not
     * from the input size.
     *
     * @param strokes user strokes in normalized 0..1 coordinates
     * @return the ready-to-use prompt, or null when the strokes list is empty
     * (or no stroke has a single point)
     */
    public Prompts convertStrokesToPrompts(List<EditorStroke> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return null;
        }
        // union bounding box of all strokes + total point count for the
        // proportional sampling distribution
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        int totalPointCount = 0;
        for (EditorStroke stroke : strokes) {
            int strokePointCount = stroke.getPoints().size() / 2;
            if (strokePointCount == 0) {
                // empty stroke (no point), contributes nothing
                continue;
            }
            float[] bbox = stroke.getBoundingBox();
            minX = Math.min(minX, bbox[0]);
            minY = Math.min(minY, bbox[1]);
            maxX = Math.max(maxX, bbox[2]);
            maxY = Math.max(maxY, bbox[3]);
            totalPointCount += strokePointCount;
        }
        if (totalPointCount == 0) {
            return null;
        }
        if (maxX <= minX || maxY <= minY) {
            // degenerate marking (single taps or a straight line): a box
            // prompt would have zero extent, the points-only set marks the
            // object through foreground points alone
            return pointsOnlyPrompts(strokes, totalPointCount);
        }
        float[][] points = new float[MAX_PROMPT_ENTRIES][2];
        float[] labels = new float[MAX_PROMPT_ENTRIES];
        // padding label for the unused trailing entries
        Arrays.fill(labels, -1f);
        // box entries first (the model contract: 2 = top-left, 3 = bottom-right),
        // the normalized points map into the model input space directly
        // (see the method javadoc)
        points[0][0] = minX * SAM_MODEL_INPUT_SIZE;
        points[0][1] = minY * SAM_MODEL_INPUT_SIZE;
        labels[0] = PROMPT_LABEL_BOX_TOP_LEFT;
        points[1][0] = maxX * SAM_MODEL_INPUT_SIZE;
        points[1][1] = maxY * SAM_MODEL_INPUT_SIZE;
        labels[1] = PROMPT_LABEL_BOX_BOTTOM_RIGHT;
        // sample up to MAX_SAMPLED_POINTS foreground points evenly along the
        // stroke paths, distributed proportionally to each stroke's point count
        int sampledBudget = MAX_SAMPLED_POINTS;
        List<float[]> sampled = new ArrayList<>();
        for (EditorStroke stroke : strokes) {
            if (sampledBudget <= 0) {
                break;
            }
            ArrayList<Float> strokePoints = stroke.getPoints();
            int strokePointCount = strokePoints.size() / 2;
            if (strokePointCount == 0 || totalPointCount == 0) {
                continue;
            }
            int strokeSamples = Math.min(sampledBudget,
                    Math.max(1, Math.round((float) MAX_SAMPLED_POINTS
                            * strokePointCount / totalPointCount)));
            for (int i = 0; i < strokeSamples; i++) {
                int pointIndex = strokePointCount * i / strokeSamples;
                float x = strokePoints.get(pointIndex * 2);
                float y = strokePoints.get(pointIndex * 2 + 1);
                float scaledX = x * SAM_MODEL_INPUT_SIZE;
                float scaledY = y * SAM_MODEL_INPUT_SIZE;
                if (isDuplicatePoint(sampled, scaledX, scaledY)) {
                    continue;
                }
                sampled.add(new float[]{scaledX, scaledY});
                sampledBudget--;
                if (sampledBudget <= 0) {
                    break;
                }
            }
        }
        for (int i = 0; i < sampled.size(); i++) {
            points[2 + i][0] = sampled.get(i)[0];
            points[2 + i][1] = sampled.get(i)[1];
            labels[2 + i] = PROMPT_LABEL_FOREGROUND;
        }
        return new Prompts(points, labels);
    }

    /**
     * Points-only prompt set for a degenerate marking (single taps or a
     * straight line whose bounding box has zero extent): the marked points
     * sampled evenly along the stroke paths become the foreground prompt
     * (label 1, up to {@link #MAX_PROMPT_ENTRIES} of them — no box entries
     * are used so the full cap is available), the unused trailing entries
     * stay padded with coordinates 0,0 and label -1
     *
     * @param strokes         user strokes in normalized 0..1 coordinates
     * @param totalPointCount total point count of the strokes, the sampling
     *                        distribution base
     * @return the ready-to-use prompt
     */
    private Prompts pointsOnlyPrompts(List<EditorStroke> strokes,
                                      int totalPointCount) {
        float[][] points = new float[MAX_PROMPT_ENTRIES][2];
        float[] labels = new float[MAX_PROMPT_ENTRIES];
        // padding label for the unused trailing entries
        Arrays.fill(labels, -1f);
        int sampledBudget = MAX_PROMPT_ENTRIES;
        List<float[]> sampled = new ArrayList<>();
        for (EditorStroke stroke : strokes) {
            if (sampledBudget <= 0) {
                break;
            }
            ArrayList<Float> strokePoints = stroke.getPoints();
            int strokePointCount = strokePoints.size() / 2;
            if (strokePointCount == 0) {
                continue;
            }
            int strokeSamples = Math.min(sampledBudget,
                    Math.max(1, Math.round((float) MAX_PROMPT_ENTRIES
                            * strokePointCount / totalPointCount)));
            for (int i = 0; i < strokeSamples; i++) {
                int pointIndex = strokePointCount * i / strokeSamples;
                float x = strokePoints.get(pointIndex * 2);
                float y = strokePoints.get(pointIndex * 2 + 1);
                float scaledX = x * SAM_MODEL_INPUT_SIZE;
                float scaledY = y * SAM_MODEL_INPUT_SIZE;
                if (isDuplicatePoint(sampled, scaledX, scaledY)) {
                    continue;
                }
                sampled.add(new float[]{scaledX, scaledY});
                sampledBudget--;
                if (sampledBudget <= 0) {
                    break;
                }
            }
        }
        for (int i = 0; i < sampled.size(); i++) {
            points[i][0] = sampled.get(i)[0];
            points[i][1] = sampled.get(i)[1];
            labels[i] = PROMPT_LABEL_FOREGROUND;
        }
        return new Prompts(points, labels);
    }

    /**
     * Compute the binary mask (CV_8U, 0..255, 255 = object) of the marked
     * objects in the given bitmap, upsampled to the bitmap dimensions.
     * <p>
     * SAM segments ONE object per prompt set, so the strokes are first grouped
     * into their connected painted components (the strokes are rasterized and
     * {@link Imgproc#connectedComponents} splits the painting; every stroke
     * joins the group of its first point, a stroke path is contiguous so all
     * its pixels share one component): one prompt set (the group bounding box
     * plus foreground points sampled from the group's strokes) and one SAM run
     * per group, largest groups first by point count and capped at
     * {@link #MAX_MASK_GROUPS}. The raw per-group masks are unioned with
     * {@link Core#max} and the union is feathered and upsampled once; a single
     * painted group degenerates to exactly one SAM run with the same result as
     * a direct single-prompt computation.
     * <p>
     * The mask is feathered at the model resolution so the overlay display
     * and the erase paste-back do not end with an aliased hard edge.
     *
     * @param bitmap  bitmap to be segmented, never recycled by this method
     * @param strokes user strokes in normalized 0..1 coordinates drawn over
     *                the bitmap
     * @return the mask handle, the caller owns it and must release() it,
     * or null when the strokes list is empty
     */
    public MagicEraserMask computeObjectMask(Bitmap bitmap, List<EditorStroke> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return null;
        }
        EfficientSamProcessor processor = mMLEngine.get().getEfficientSamProcessor();
        Mat painted = null;
        Mat labels = null;
        Mat union = null;
        try {
            // group the strokes into their connected painted components:
            // SAM cannot segment two disjoint objects from one prompt set
            // reliably, one prompt set is built and one SAM run is made per
            // group
            painted = rasterizeMask(strokes, bitmap.getWidth(), bitmap.getHeight());
            labels = new Mat();
            Imgproc.connectedComponents(painted, labels);
            Map<Integer, List<EditorStroke>> groupsByComponent = new HashMap<>();
            List<List<EditorStroke>> groups = new ArrayList<>();
            int[] pixelLabel = new int[1];
            for (EditorStroke stroke : strokes) {
                ArrayList<Float> points = stroke.getPoints();
                if (points.size() < 2) {
                    continue;
                }
                // the round cap at the first point paints its rounded pixel,
                // the label of that pixel is the stroke's group
                int px = clampToSize(Math.round(points.get(0) * bitmap.getWidth()),
                        bitmap.getWidth());
                int py = clampToSize(Math.round(points.get(1) * bitmap.getHeight()),
                        bitmap.getHeight());
                labels.get(py, px, pixelLabel);
                List<EditorStroke> group = groupsByComponent.get(pixelLabel[0]);
                if (group == null) {
                    group = new ArrayList<>();
                    groupsByComponent.put(pixelLabel[0], group);
                    groups.add(group);
                }
                group.add(stroke);
            }
            // largest groups first by point count, the small disconnected
            // taps are skipped when the cap is reached
            groups.sort((a, b) -> groupPointCount(b) - groupPointCount(a));
            int processed = Math.min(groups.size(), MAX_MASK_GROUPS);
            spikeLog("object groups: " + groups.size()
                    + ", processing: " + processed);
            for (int i = 0; i < processed; i++) {
                Prompts prompts = convertStrokesToPrompts(groups.get(i));
                if (prompts == null) {
                    continue;
                }
                String logPrefix = "group " + (i + 1) + "/" + processed + ": ";
                Mat groupMask = processor.computeRawMask(bitmap,
                        prompts.mPoints, prompts.mLabels, logPrefix);
                try {
                    if (union == null) {
                        // the first group mask becomes the union (ownership
                        // moved, released with the union)
                        union = groupMask;
                        groupMask = null;
                    } else {
                        // per-element maximum into a FRESH dst: in-place
                        // aliasing (dst == src1) is not guaranteed by these
                        // bindings
                        Mat merged = new Mat();
                        try {
                            Core.max(union, groupMask, merged);
                        } catch (RuntimeException | OutOfMemoryError e) {
                            merged.release();
                            throw e;
                        }
                        union.release();
                        union = merged;
                    }
                } finally {
                    // on the max path the group mask is always released here
                    // (only the first group mask is transferred to the union)
                    if (groupMask != null) {
                        groupMask.release();
                    }
                }
            }
            if (union == null) {
                return null;
            }
            // feather the segmentation edge of the union once, sigma 0 lets
            // OpenCV derive it from the kernel; the kernel scales with the
            // raw mask's ACTUAL plane side (the export's fixed 1024² output,
            // see EfficientSamProcessor), not with the model input size
            Imgproc.GaussianBlur(union, union,
                    new Size(samMaskFeatherKernel(union.cols()),
                            samMaskFeatherKernel(union.cols())), 0);
            // upsample the mask to the input bitmap resolution once
            Imgproc.resize(union, union,
                    new Size(bitmap.getWidth(), bitmap.getHeight()),
                    0, 0, Imgproc.INTER_LINEAR);
            return new MagicEraserMask(union);
        } catch (RuntimeException | OutOfMemoryError e) {
            if (union != null) {
                union.release();
            }
            throw e;
        } finally {
            if (painted != null) {
                painted.release();
            }
            if (labels != null) {
                labels.release();
            }
        }
    }

    /**
     * Check whether the given mask contains an object, so the caller can
     * report "no object detected" instead of running a no-op erase.
     *
     * @param mask mask handle created by computeObjectMask
     * @return true when the masked area (sum of opaque pixel values) covers
     * at least {@link #MIN_MASK_AREA_RATIO} of the image area
     */
    public boolean hasMask(MagicEraserMask mask) {
        return hasMaskImpl(mask.mMask);
    }

    /**
     * Mask detection of the raw native mask Mat, package-private so the
     * processor level helpers of this package reuse the exact computation
     */
    static boolean hasMaskImpl(Mat mask) {
        if (mask.empty()) {
            return false;
        }
        double area = (double) mask.cols() * mask.rows();
        double sum = Core.sumElems(mask).val[0];
        return sum >= area * 255.0 * MIN_MASK_AREA_RATIO;
    }

    /**
     * Rasterize the user strokes into a single channel CV_8U mask of the
     * given dimensions: each stroke is drawn as thick line segments
     * (anti-aliased) with round caps at every point, the thickness scales
     * with the stroke brush size (see {@link EditorStroke}).
     * Package-private on purpose: the raw Mat return type must stay out of
     * this engine's public API (the :app module has no org.opencv on its
     * compile classpath), the mask rasterization is engine/test-internal and
     * never part of the editor flow.
     *
     * @param strokes user strokes in normalized 0..1 coordinates
     * @param width   mask width
     * @param height  mask height
     * @return the rasterized mask Mat (0 background, 255 stroke), the caller
     * owns it and must release() it
     */
    Mat rasterizeMask(List<EditorStroke> strokes, int width, int height) {
        // the scalar initializer zeroes the whole Mat, an uninitialized Mat
        // would carry random memory content
        Mat mask = new Mat(height, width, CvType.CV_8U, new Scalar(0));
        int minDimension = Math.min(width, height);
        try {
            for (EditorStroke stroke : strokes) {
                ArrayList<Float> points = stroke.getPoints();
                int pointCount = points.size() / 2;
                if (pointCount == 0) {
                    continue;
                }
                // thickness per brush size step (size 1 -> 1%, size 5 -> 5%
                // of the min dimension)
                int thickness = Math.max(1, Math.round(
                        stroke.getBrushSize() * BRUSH_THICKNESS_SCALE * minDimension));
                Scalar color = new Scalar(MASK_VALUE);
                for (int i = 0; i < pointCount; i++) {
                    float x = points.get(i * 2);
                    float y = points.get(i * 2 + 1);
                    int px = Math.round(x * width);
                    int py = Math.round(y * height);
                    if (i > 0) {
                        float prevX = points.get((i - 1) * 2);
                        float prevY = points.get((i - 1) * 2 + 1);
                        int prevPx = Math.round(prevX * width);
                        int prevPy = Math.round(prevY * height);
                        Imgproc.line(mask, new org.opencv.core.Point(prevPx, prevPy),
                                new org.opencv.core.Point(px, py), color,
                                thickness, Imgproc.LINE_AA);
                    }
                    // round cap at every point so isolated taps and the joint
                    // between segments are covered
                    Imgproc.circle(mask, new org.opencv.core.Point(px, py),
                            thickness / 2, color, -1, Imgproc.LINE_AA);
                }
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            mask.release();
            throw e;
        }
        return mask;
    }

    /**
     * Erase the masked object from the given bitmap: the SAM mask is
     * binarized (LaMa requires a hard mask, see the LamaProcessor model
     * contract), the region around the mask is inpainted by LaMa and the
     * result is pasted back only over the masked pixels with a feathered
     * edge (the feather lives only at the paste-back, the inpainting itself
     * runs on the hard binary mask).
     *
     * @param bitmap  bitmap to be edited, never modified and never recycled
     *                by this method
     * @param samMask object mask handle created by computeObjectMask, must
     *                match the bitmap dimensions, not released by this method
     * @return a new mutable ARGB_8888 bitmap with the masked object erased,
     * the caller owns it and must recycle() it
     */
    public Bitmap eraseRegion(Bitmap bitmap, MagicEraserMask samMask) {
        checkMask(samMask.mMask, bitmap);
        Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Mat rgba = new Mat();
        Mat hardMask = new Mat();
        try {
            Utils.bitmapToMat(result, rgba);
            // binarize the feathered SAM mask: never feed a feathered mask to
            // LaMa (its binarization requires hard 0/1 values). Core.compare is
            // used instead of Imgproc.threshold: the compare (src1, src2, dst)
            // argument order is unambiguous, while the erase tests failed with
            // an empty hardMask after the threshold call on the 5.0.0.1
            // bindings (the docs keep threshold src-first, but the 5.x
            // thresholdWithMask overload shares the dispatch, so compare is
            // the safe path). Same semantics as THRESH_BINARY at 127:
            // pixel > 127 becomes 255, everything else 0
            Core.compare(samMask.mMask, new Scalar(MASK_BINARY_THRESHOLD),
                    hardMask, Core.CMP_GT);
            List<Rect> regions = computeInpaintRegions(hardMask,
                    bitmap.getWidth(), bitmap.getHeight());
            for (Rect region : regions) {
                inpaintRegion(rgba, hardMask, region);
            }
            Utils.matToBitmap(rgba, result);
        } catch (RuntimeException | OutOfMemoryError e) {
            result.recycle();
            throw e;
        } finally {
            rgba.release();
            hardMask.release();
        }
        return result;
    }

    /**
     * Run the full erase pipeline on the given preview bitmap: replay the
     * committed batch history on it (so this erase operates on the image the
     * consumer currently sees — without the replay, erase #2 would resurrect
     * erase #1's object on screen while save/share/restore would stay
     * correct), reuse the handed-over mask when given or segment the marked
     * object fresh, and inpaint the masked region away at the preview
     * resolution.
     * <p>
     * Ownership: the caller TRANSFERS OWNERSHIP of both the preview bitmap
     * and the reusable mask to this method — the mask is released exactly
     * once in the finally below (whether it was reused, freshly computed or
     * absent) and the preview is recycled (the replay recycles its
     * intermediates), while the returned result bitmap is caller owned.
     * The cancelled check fires before the SAM forward (when no mask was
     * handed over) and before the LaMa forward: a dead consumer must not
     * re-parse the released nets into the app-scoped processor cache nobody
     * will release anymore.
     *
     * @param preview      bitmap to be erased, ownership is transferred to
     *                     this method (recycled in the finally)
     * @param history      the committed erase batches, replayed on the
     *                     preview before this erase
     * @param strokes      the strokes of this erase
     * @param reusableMask the reusable mask of the last detect or null,
     *                     ownership is transferred to this method (released
     *                     exactly once in the finally)
     * @param cancelled    abort hook, checked before every native forward so
     *                     a dead consumer can stop the pipeline
     * @return the erase result, its bitmap is caller owned (null with
     * noObject set when the segmentation found no object)
     */
    public EraseObjectResult eraseObject(Bitmap preview, List<StrokeBatch> history,
                                         ArrayList<EditorStroke> strokes,
                                         MagicEraserMask reusableMask,
                                         CancelledCheck cancelled) {
        MagicEraserMask mask = reusableMask;
        try {
            // replay the committed batches before this erase (same replay as
            // restoreEdits/save/share run): the strokes are normalized and
            // the masks are computed on the image they segment, so the
            // replayed preview is the correct base for the current mask and
            // the composited result matches the persisted replay of the full
            // history including this batch
            preview = applyBatches(preview, history, cancelled);
            if (mask == null) {
                if (cancelled.isCancelled()) {
                    // the consumer was disposed while this erase ran: abort
                    // before the SAM forward re-parses the released net into
                    // the app-scoped processor cache nobody will release
                    // anymore (the finally below releases the mask and the
                    // preview; the disposed Rx chain never delivers the
                    // failure to the dead consumer)
                    throw new IllegalStateException(
                            "MagicEraserEngine erase aborted: the consumer is cancelled");
                }
                mask = computeObjectMask(preview, strokes);
            }
            if (mask == null) {
                return new EraseObjectResult(null, true);
            }
            if (!hasMask(mask)) {
                return new EraseObjectResult(null, true);
            }
            if (cancelled.isCancelled()) {
                // the consumer was disposed while this erase ran: abort
                // before the LaMa forward re-parses the released net into
                // the app-scoped processor cache nobody will release
                // anymore (the finally below releases the mask and the
                // preview; the disposed Rx chain never delivers the failure
                // to the dead consumer)
                throw new IllegalStateException(
                        "MagicEraserEngine erase aborted: the consumer is cancelled");
            }
            return new EraseObjectResult(eraseRegion(preview, mask), false);
        } finally {
            // the reusable mask ownership moved into this method (caller
            // transfers ownership), it is released here exactly once
            if (mask != null) {
                mask.release();
            }
            if (preview != null) {
                preview.recycle();
            }
        }
    }

    /**
     * Replay the committed batches over the given base bitmap at its
     * resolution: per batch the marked object is re-segmented and inpainted
     * away. Every intermediate bitmap is recycled, the returned bitmap is
     * caller owned. Runs on a background thread.
     *
     * @param base      bitmap to replay on, recycled here once a batch
     *                  applied to it (the caller must not use it afterwards)
     * @param batches   the committed erase batches, replayed in order
     * @param cancelled abort hook, checked at the top of every iteration so
     *                  a dead consumer can stop the replay
     * @return the replayed bitmap, caller owned
     */
    public Bitmap applyBatches(Bitmap base, List<StrokeBatch> batches,
                               CancelledCheck cancelled) {
        Bitmap current = base;
        try {
            for (StrokeBatch batch : batches) {
                if (cancelled.isCancelled()) {
                    // the consumer was disposed mid-replay: abort before the
                    // next native run re-parses the nets into the app-scoped
                    // processor cache nobody will release anymore (the catch
                    // below recycles the current bitmap; the disposed Rx
                    // chain never delivers the failure to the dead consumer)
                    throw new IllegalStateException(
                            "MagicEraserEngine batch replay aborted: the consumer is cancelled");
                }
                MagicEraserMask mask =
                        computeObjectMask(current, batch.getStrokes());
                if (mask == null) {
                    continue;
                }
                try {
                    if (!hasMask(mask)) {
                        continue;
                    }
                    Bitmap erased = eraseRegion(current, mask);
                    current.recycle();
                    current = erased;
                } finally {
                    mask.release();
                }
            }
            return current;
        } catch (Exception | OutOfMemoryError e) {
            current.recycle();
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError) instead of
            // delivering them to the error consumer, wrap it into a runtime exception
            // so it is handled as a pipeline failure
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Convert the CV_8U mask handle into a white tint ARGB_8888 bitmap at
     * {@link #OVERLAY_TINT_ALPHA_SCALE} opacity, so the :app editor overlay
     * can display the mask without importing org.opencv.
     *
     * @param mask mask handle created by computeObjectMask, not released by
     *             this method
     * @return the mask bitmap, the caller owns it and must recycle() it
     */
    public Bitmap toOverlayBitmap(MagicEraserMask mask) {
        return toOverlayBitmapImpl(mask.mMask);
    }

    /**
     * Convert the CV_8U mask into a white tint ARGB_8888 bitmap at
     * {@link #OVERLAY_TINT_ALPHA_SCALE} opacity, ready to be drawn as an
     * overlay. The mask itself is never mutated (the stored
     * {@link MagicEraserMask} is reused by the erase, which hard-thresholds
     * at 127): a clone is scaled to the display opacity, merged and released
     * in finally.
     * matToBitmap supports the verified CV_8UC4 &lt;-&gt; ARGB_8888 pair, so the
     * single channel mask is merged into an RGBA Mat first. All four merged
     * channels are the scaled mask itself (RGB = alpha = scaled mask value):
     * Android ARGB_8888 bitmaps are premultiplied by default and matToBitmap
     * copies the Mat bytes straight into that premultiplied storage, so
     * merging constant white would produce INVALID premultiplied data
     * (RGB=255 under alpha=0) which the framework composites as full white
     * on every pixel of the draw rect — the all-white overlay regression
     * this replaced. RGB = alpha is exactly the valid premultiplied
     * representation of white with the mask opacity: it contributes white at
     * alpha strength and nothing where the mask is 0 (RGB = alpha of the
     * SCALED values is still the valid premultiplied white at the tint
     * opacity).
     * <p>
     * Named with the Impl suffix (like hasMaskImpl) so no public method of
     * this class shares a name with an org.opencv-signature overload: the
     * :app javac resolves overloads per name and could not even read a
     * Mat-typed sibling descriptor (org.opencv is not on its compile
     * classpath).
     */
    private Bitmap toOverlayBitmapImpl(Mat mask) {
        int width = mask.cols();
        int height = mask.rows();
        Mat rgba = null;
        // the mask must NOT be mutated (it is reused by the erase), the
        // display-only tint is scaled on a clone
        Mat tinted = mask.clone();
        try {
            rgba = new Mat();
            tinted.convertTo(tinted, CvType.CV_8U, OVERLAY_TINT_ALPHA_SCALE);
            // every list handed to an org.opencv API must be a real
            // java.util.ArrayList instance (see EfficientSamProcessor: the
            // OpenCV 5 JNI list handling aborts under CheckJNI on an
            // Arrays.asList view)
            List<Mat> rgbaChannels = new ArrayList<>();
            rgbaChannels.add(tinted);
            rgbaChannels.add(tinted);
            rgbaChannels.add(tinted);
            rgbaChannels.add(tinted);
            Core.merge(rgbaChannels, rgba);
            Bitmap maskBitmap = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            try {
                Utils.matToBitmap(rgba, maskBitmap);
            } catch (RuntimeException | OutOfMemoryError e) {
                maskBitmap.recycle();
                throw e;
            }
            return maskBitmap;
        } finally {
            tinted.release();
            if (rgba != null) {
                rgba.release();
            }
        }
    }

    /**
     * Determine the image regions to inpaint. The expanded mask bounding box
     * is the default single region; when it covers more than
     * {@link #MAX_REGION_AREA_RATIO} of the image the mask is split into its
     * connected components (largest first, capped at {@link #MAX_COMPONENTS})
     * and each component gets its own expanded crop, falling back to the
     * whole-image rect for a single component that still covers most of the
     * image (the whole-image downscale fallback of the reference).
     */
    private List<Rect> computeInpaintRegions(Mat hardMask, int width, int height) {
        List<Rect> regions = new ArrayList<>();
        // OpenCV 5 Java bindings: findNonZero keeps the C++ argument order
        // (src first, idx second — the earlier destination-first reading came
        // from an unverified javap inference, and passing the empty
        // destination as src trips the "src.dims == 2" assertion), and
        // boundingRect lives in org.opencv.geometry.Geometry
        Mat nonZero = new Mat();
        Rect maskRect;
        try {
            Core.findNonZero(hardMask, nonZero);
            if (nonZero.empty()) {
                return regions;
            }
            maskRect = Geometry.boundingRect(nonZero);
        } finally {
            nonZero.release();
        }
        Rect expanded = expandRect(maskRect, width, height);
        double imageArea = (double) width * height;
        if ((double) expanded.area() <= imageArea * MAX_REGION_AREA_RATIO) {
            regions.add(expanded);
            return regions;
        }
        // the mask covers most of the image, split it into its connected
        // components and inpaint each component in its own crop
        Mat labels = new Mat();
        try {
            int componentCount = Imgproc.connectedComponents(hardMask, labels);
            if (componentCount <= 1) {
                // a single component (only the background label) that still
                // covers most of the image, whole-image downscale fallback
                regions.add(new Rect(0, 0, width, height));
                return regions;
            }
            // one pass over the label image accumulates the area and the
            // bounds of every component (labels is a continuous CV_32S blob)
            int[] labelBuffer = new int[(int) labels.total()];
            labels.get(0, 0, labelBuffer);
            int[] areas = new int[componentCount];
            int[] minXs = new int[componentCount];
            int[] minYs = new int[componentCount];
            int[] maxXs = new int[componentCount];
            int[] maxYs = new int[componentCount];
            for (int i = 0; i < componentCount; i++) {
                minXs[i] = Integer.MAX_VALUE;
                minYs[i] = Integer.MAX_VALUE;
                maxXs[i] = -1;
                maxYs[i] = -1;
            }
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int label = labelBuffer[rowOffset + x];
                    areas[label]++;
                    if (x < minXs[label]) {
                        minXs[label] = x;
                    }
                    if (y < minYs[label]) {
                        minYs[label] = y;
                    }
                    if (x > maxXs[label]) {
                        maxXs[label] = x;
                    }
                    if (y > maxYs[label]) {
                        maxYs[label] = y;
                    }
                }
            }
            // largest components first, the small scattered ones are skipped
            // when the cap is reached
            Integer[] componentIndexes = new Integer[componentCount - 1];
            for (int i = 1; i < componentCount; i++) {
                componentIndexes[i - 1] = i;
            }
            Arrays.sort(componentIndexes, (a, b) -> areas[b] - areas[a]);
            int processed = Math.min(componentIndexes.length, MAX_COMPONENTS);
            for (int i = 0; i < processed; i++) {
                int component = componentIndexes[i];
                Rect componentRect = new Rect(minXs[component], minYs[component],
                        maxXs[component] - minXs[component] + 1,
                        maxYs[component] - minYs[component] + 1);
                Rect componentExpanded = expandRect(componentRect, width, height);
                if ((double) componentExpanded.area() > imageArea * MAX_REGION_AREA_RATIO) {
                    // a single component still covering most of the image,
                    // whole-image downscale fallback for it
                    regions.add(new Rect(0, 0, width, height));
                } else {
                    regions.add(componentExpanded);
                }
            }
        } finally {
            labels.release();
        }
        return regions;
    }

    /**
     * Inpaint one image region: the RGBA submat of the region is converted to
     * BGR, inpainted by LaMa against the hard mask submat and pasted back
     * only over the masked pixels with a feathered mask. Every intermediate
     * Mat is released, the submat views are released before the parent Mats
     * (a submat release only frees the header, the parent data stays alive).
     */
    private void inpaintRegion(Mat rgba, Mat hardMask, Rect region) {
        Mat bitmapRegion = null;
        Mat maskRegion = null;
        Mat bgrRegion = null;
        Mat inpainted = null;
        try {
            // submat views into the parent mats (no data copy)
            bitmapRegion = new Mat(rgba, region);
            maskRegion = new Mat(hardMask, region);
            bgrRegion = new Mat();
            Imgproc.cvtColor(bitmapRegion, bgrRegion, Imgproc.COLOR_RGBA2BGR);
            inpainted = mMLEngine.get().getLamaProcessor()
                    .inpaint(bgrRegion, maskRegion);
            // paste back only the masked pixels: the feathered mask blends
            // the patch boundary into the surrounding image
            int featherKernel = toOdd(Math.round(
                    Math.min(region.width, region.height) * PASTE_FEATHER_SCALE));
            Mat featheredMask = null;
            Mat inpaintedRgba = null;
            try {
                featheredMask = new Mat();
                Imgproc.GaussianBlur(maskRegion, featheredMask,
                        new Size(featherKernel, featherKernel), 0);
                inpaintedRgba = new Mat();
                Imgproc.cvtColor(inpainted, inpaintedRgba, Imgproc.COLOR_BGR2RGBA);
                Core.copyTo(inpaintedRgba, bitmapRegion, featheredMask);
            } finally {
                if (inpaintedRgba != null) {
                    inpaintedRgba.release();
                }
                if (featheredMask != null) {
                    featheredMask.release();
                }
            }
        } finally {
            if (inpainted != null) {
                inpainted.release();
            }
            if (bgrRegion != null) {
                bgrRegion.release();
            }
            if (maskRegion != null) {
                maskRegion.release();
            }
            if (bitmapRegion != null) {
                bitmapRegion.release();
            }
        }
    }

    /**
     * Expand the given rect by ~50% of its size on each side and clamp it
     * into the image bounds
     */
    private static Rect expandRect(Rect rect, int width, int height) {
        int expandX = Math.round(rect.width * REGION_EXPAND_SCALE);
        int expandY = Math.round(rect.height * REGION_EXPAND_SCALE);
        int left = Math.max(0, rect.x - expandX);
        int top = Math.max(0, rect.y - expandY);
        int right = Math.min(width, rect.x + rect.width + expandX);
        int bottom = Math.min(height, rect.y + rect.height + expandY);
        return new Rect(left, top, right - left, bottom - top);
    }

    private static boolean isDuplicatePoint(List<float[]> sampled, float x, float y) {
        for (float[] point : sampled) {
            if (point[0] == x && point[1] == y) {
                return true;
            }
        }
        return false;
    }

    /**
     * Total point count of a stroke group, the groups are processed largest
     * first so the biggest painted objects are segmented before the cap cuts
     * the rest
     */
    private static int groupPointCount(List<EditorStroke> group) {
        int count = 0;
        for (EditorStroke stroke : group) {
            count += stroke.getPoints().size() / 2;
        }
        return count;
    }

    /**
     * Clamp a pixel coordinate into the image bounds (a normalized point of
     * 1.0 rounds up to the size itself, one past the last pixel)
     */
    private static int clampToSize(int value, int size) {
        if (value < 0) {
            return 0;
        }
        if (value > size - 1) {
            return size - 1;
        }
        return value;
    }

    /**
     * Group-processing diagnostics printer, dead code unless
     * {@link #SPIKE_LOG} is flipped to true for a spike run: the mLogger
     * debug level is not a release gate (the file logger still writes debug
     * messages in release builds, see BaseProviderModule), so these clearly
     * marked System.out lines stay behind a compile-time constant. They
     * exist because the mLogger output does not reach logcat from the
     * instrumented test process, the connectedCheck spike run iterates from
     * them.
     */
    private static void spikeLog(String message) {
        if (SPIKE_LOG) {
            System.out.println("SPIKE: " + message);
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

    /**
     * The compositing reads the mask pixels aligned with the bitmap pixels,
     * a dimension mismatch would produce a shifted or clipped mask silently
     */
    private static void checkMask(Mat mask, Bitmap bitmap) {
        if (mask.cols() != bitmap.getWidth()
                || mask.rows() != bitmap.getHeight()) {
            throw new IllegalArgumentException("Mask dimensions ("
                    + mask.cols() + "x" + mask.rows()
                    + ") must match the bitmap dimensions ("
                    + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
        }
    }
}
