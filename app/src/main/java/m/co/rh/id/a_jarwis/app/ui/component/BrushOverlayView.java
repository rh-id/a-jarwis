package m.co.rh.id.a_jarwis.app.ui.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;

/**
 * Transparent overlay drawn on top of the magic eraser editor image view,
 * capturing the brush strokes the user paints over the object to be erased.
 * The strokes are stored normalized (0..1, resolution-independent, see
 * {@link EditorStroke}), the view maps them through the current image matrix
 * for display and inverts it to map the touch input back to normalized
 * coordinates. The view only draws vector paths (no bitmap ops), the stroke
 * width follows the brush size scaled through the matrix so the drawn stroke
 * tracks the image zoom.
 * <p>
 * The view itself is transient (a plain field of the editor page), the
 * committed strokes live in the serializable page state: the page pushes them
 * back with {@link #setStrokes(List)} on every rebuild.
 */
public class BrushOverlayView extends View {

    /**
     * Listener notified when a stroke is committed (the finger was lifted),
     * the editor page triggers its mask refresh on it
     */
    public interface OnStrokeCommittedListener {
        void onStrokeCommitted();
    }

    /**
     * Semi-transparent magenta brush color, visible over dark and light image
     * regions alike
     */
    private static final int BRUSH_COLOR = 0x80E91E63;
    /**
     * Normalized stroke thickness per brush size step (see the MagicEraserEngine
     * mask rasterization: 1% of the image min dimension per size step, and the
     * min dimension is 1.0 in normalized space)
     */
    private static final float BRUSH_THICKNESS_SCALE = 0.01f;
    /**
     * Length (px) of the tiny segment added to a tap-only stroke, the round
     * paint cap renders it as a dot (a moveTo-only path draws nothing)
     */
    private static final float TAP_DOT_SEGMENT = 0.5f;

    private final Paint mStrokePaint;
    private final Matrix mMatrix = new Matrix();
    private final Matrix mInverseMatrix = new Matrix();
    /**
     * The paths are kept in view coordinates, rebuilt whenever the strokes or
     * the matrix change (vector drawing only, no bitmap ops)
     */
    private final Path mStrokesPath = new Path();
    private final Path mCurrentPath = new Path();
    /**
     * The committed strokes, a reference to the page state list
     * (resolution-independent normalized points)
     */
    private List<EditorStroke> mStrokes;
    private EditorStroke mCurrentStroke;
    private OnStrokeCommittedListener mOnStrokeCommittedListener;
    private int mBrushSize = EditorStroke.BRUSH_SIZE_DEFAULT;
    /**
     * true while a detected mask overlay is displayed over the image: the
     * committed strokes are hidden (the mask tint already marks the region),
     * an in-progress stroke stays visible. Page-owned display state, the
     * page pushes it with {@link #setMaskDisplayed(boolean)}
     */
    private boolean mMaskDisplayed;
    /**
     * Touch slop converted into the normalized image space, a finger move
     * below it does not add a point (jitter filter)
     */
    private float mNormalizedTouchSlop;
    private float mStrokeWidth;

    public BrushOverlayView(Context context) {
        super(context);
        mStrokePaint = createPaint();
        init();
    }

    public BrushOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStrokePaint = createPaint();
        init();
    }

    private static Paint createPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(BRUSH_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    private void init() {
        // view-space value until setMatrix converts it into the normalized
        // image space, touches are ignored while the matrix is not pushed
        mNormalizedTouchSlop = 0f;
    }

    public void setOnStrokeCommittedListener(OnStrokeCommittedListener listener) {
        mOnStrokeCommittedListener = listener;
    }

    /**
     * Replace the committed strokes with the given page state list, the view
     * keeps a reference (it never mutates the list)
     */
    public void setStrokes(List<EditorStroke> strokes) {
        mStrokes = strokes;
        mCurrentStroke = null;
        rebuildPaths();
    }

    /**
     * Push the current image matrix (normalized image space to view space,
     * supplied by the editor page from the image view), a copy is kept and
     * inverted for the touch mapping
     */
    public void setMatrix(Matrix matrix) {
        if (matrix == null) {
            return;
        }
        mMatrix.set(matrix);
        mMatrix.invert(mInverseMatrix);
        mStrokeWidth = mBrushSize * BRUSH_THICKNESS_SCALE
                * mMatrix.mapRadius(1f);
        // the slop is scaled like a stroke width so the jitter filter stays
        // the same physical finger distance at any zoom level
        mNormalizedTouchSlop = ViewConfiguration.get(getContext())
                .getScaledTouchSlop() / Math.max(1f, mMatrix.mapRadius(1f));
        rebuildPaths();
    }

    /**
     * Set the current brush size (1..5), only affects the strokes drawn
     * afterwards
     */
    public void setBrushSize(int brushSize) {
        mBrushSize = brushSize;
        if (!mMatrix.isIdentity()) {
            mStrokeWidth = mBrushSize * BRUSH_THICKNESS_SCALE
                    * mMatrix.mapRadius(1f);
        }
        invalidate();
    }

    /**
     * Discard the in-progress stroke (not yet committed), the committed
     * strokes stay untouched
     */
    public void clearCurrentStroke() {
        mCurrentStroke = null;
        mCurrentPath.reset();
        invalidate();
    }

    /**
     * Set whether a detected mask overlay is currently displayed over the
     * image: while true the committed strokes are skipped in onDraw (the
     * mask tint already marks the region, keeping the strokes visible would
     * only clutter it), the in-progress stroke is always drawn so the user
     * still sees what they are painting. Page-owned display state, only
     * this setter changes it — rebuilding the paths or clearing the current
     * stroke must not touch it.
     */
    public void setMaskDisplayed(boolean displayed) {
        mMaskDisplayed = displayed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mMatrix.isIdentity() || (mStrokesPath.isEmpty() && mCurrentPath.isEmpty())) {
            return;
        }
        mStrokePaint.setStrokeWidth(mStrokeWidth);
        // committed strokes stay hidden while a detected mask is displayed,
        // the in-progress stroke always stays visible
        if (!mMaskDisplayed && !mStrokesPath.isEmpty()) {
            canvas.drawPath(mStrokesPath, mStrokePaint);
        }
        if (!mCurrentPath.isEmpty()) {
            canvas.drawPath(mCurrentPath, mStrokePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mMatrix.isIdentity()) {
            // the image matrix is not pushed yet, the stroke would be mapped
            // through a wrong transform
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startStroke(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                appendPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                appendPoint(event.getX(), event.getY());
                commitStroke();
                return true;
            case MotionEvent.ACTION_CANCEL:
                // a cancelled gesture (e.g. a parent intercepted the touch)
                // discards the in-progress stroke
                clearCurrentStroke();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * Map the view coordinates back into the normalized image space and start
     * a new in-progress stroke
     */
    private void startStroke(float viewX, float viewY) {
        float[] normalized = toNormalized(viewX, viewY);
        mCurrentStroke = new EditorStroke(mBrushSize);
        mCurrentStroke.addPoint(normalized[0], normalized[1]);
        mCurrentPath.reset();
        appendToCurrentPath(normalized[0], normalized[1]);
        invalidate();
    }

    /**
     * Map the view coordinates into the normalized image space and append the
     * point to the in-progress stroke when the finger moved further than the
     * touch slop (in image space, so the filter is zoom independent)
     */
    private void appendPoint(float viewX, float viewY) {
        if (mCurrentStroke == null) {
            return;
        }
        float[] normalized = toNormalized(viewX, viewY);
        List<Float> points = mCurrentStroke.getPoints();
        float lastX = points.get(points.size() - 2);
        float lastY = points.get(points.size() - 1);
        float dx = normalized[0] - lastX;
        float dy = normalized[1] - lastY;
        if (dx * dx + dy * dy < mNormalizedTouchSlop * mNormalizedTouchSlop) {
            return;
        }
        mCurrentStroke.addPoint(normalized[0], normalized[1]);
        appendToCurrentPath(normalized[0], normalized[1]);
        invalidate();
    }

    private void commitStroke() {
        if (mCurrentStroke == null || mStrokes == null) {
            mCurrentStroke = null;
            mCurrentPath.reset();
            invalidate();
            return;
        }
        List<Float> points = mCurrentStroke.getPoints();
        if (points.size() == 2) {
            // a single tap never moved beyond the touch slop, so the stroke
            // holds one point and the path no segment: add a tiny one so the
            // round paint cap renders a dot (a moveTo-only path draws
            // nothing, Path.isEmpty() does not help since it counts the
            // moveTo verb)
            float[] viewPoint = toView(points.get(0), points.get(1));
            mCurrentPath.lineTo(viewPoint[0] + TAP_DOT_SEGMENT,
                    viewPoint[1] + TAP_DOT_SEGMENT);
        }
        // a single tap still commits its point, rendered as the dot above
        mStrokes.add(mCurrentStroke);
        mCurrentStroke = null;
        // the current path is folded into the strokes path, no rebuild needed
        mStrokesPath.addPath(mCurrentPath);
        mCurrentPath.reset();
        invalidate();
        if (mOnStrokeCommittedListener != null) {
            mOnStrokeCommittedListener.onStrokeCommitted();
        }
    }

    private float[] toNormalized(float viewX, float viewY) {
        float[] point = new float[]{viewX, viewY};
        mInverseMatrix.mapPoints(point);
        return point;
    }

    private void appendToCurrentPath(float normalizedX, float normalizedY) {
        float[] viewPoint = toView(normalizedX, normalizedY);
        if (mCurrentPath.isEmpty()) {
            mCurrentPath.moveTo(viewPoint[0], viewPoint[1]);
        } else {
            mCurrentPath.lineTo(viewPoint[0], viewPoint[1]);
        }
    }

    private float[] toView(float normalizedX, float normalizedY) {
        float[] point = new float[]{normalizedX, normalizedY};
        mMatrix.mapPoints(point);
        return point;
    }

    /**
     * Rebuild the committed strokes path from the held list (view
     * coordinates), used when the strokes or the matrix changed
     */
    private void rebuildPaths() {
        mStrokesPath.reset();
        mCurrentPath.reset();
        if (mStrokes != null && !mMatrix.isIdentity()) {
            for (EditorStroke stroke : mStrokes) {
                List<Float> points = stroke.getPoints();
                if (points.size() < 2) {
                    continue;
                }
                float[] first = toView(points.get(0), points.get(1));
                mStrokesPath.moveTo(first[0], first[1]);
                if (points.size() == 2) {
                    // a single tap stroke: a tiny segment renders the dot via
                    // the round paint cap (a moveTo-only path draws nothing)
                    mStrokesPath.lineTo(first[0] + TAP_DOT_SEGMENT,
                            first[1] + TAP_DOT_SEGMENT);
                }
                for (int i = 2; i + 1 < points.size(); i += 2) {
                    float[] viewPoint = toView(points.get(i), points.get(i + 1));
                    mStrokesPath.lineTo(viewPoint[0], viewPoint[1]);
                }
            }
        }
        invalidate();
    }
}
