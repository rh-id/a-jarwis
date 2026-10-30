package m.co.rh.id.a_jarwis.ml_engine.model;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * A single brush stroke drawn by the user on the magic eraser overlay,
 * the stroke marks the object to be erased.
 * The points are stored normalized to the 0..1 range (relative to the image
 * width/height) so a stroke is resolution-independent: it can be rasterized
 * at any target resolution (the preview rebuild and the full resolution save
 * read the same stroke list).
 * Only holds primitive fields so it is safe to keep inside the serializable
 * editor state, never hold Bitmap/Path/Uri here.
 */
public class EditorStroke implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Minimum brush size
     */
    public static final int BRUSH_SIZE_MIN = 1;
    /**
     * Maximum brush size
     */
    public static final int BRUSH_SIZE_MAX = 5;
    /**
     * Default brush size
     */
    public static final int BRUSH_SIZE_DEFAULT = 3;

    /**
     * Interleaved normalized x,y coordinates of the stroke path (0..1),
     * size is always a multiple of 2
     */
    private ArrayList<Float> mPoints;
    /**
     * Brush size (1..5, see the BRUSH_SIZE_ constants)
     */
    private int mBrushSize;

    public EditorStroke(int brushSize) {
        mPoints = new ArrayList<>();
        mBrushSize = brushSize;
        normalize();
    }

    public ArrayList<Float> getPoints() {
        return mPoints;
    }

    /**
     * Append one normalized point to the stroke path
     *
     * @param x normalized x (0..1), clamped into the range
     * @param y normalized y (0..1), clamped into the range
     */
    public void addPoint(float x, float y) {
        mPoints.add(clamp01(x));
        mPoints.add(clamp01(y));
    }

    public int getBrushSize() {
        return mBrushSize;
    }

    /**
     * Clamp the fields to the supported values, called after deserialization
     * (and on construction) so a corrupted snapshot never propagates an invalid
     * brush size to the engine (an empty point list stays valid: an empty
     * stroke simply draws nothing)
     *
     * @return this
     */
    public EditorStroke normalize() {
        if (mPoints == null) {
            mPoints = new ArrayList<>();
        }
        if (mBrushSize < BRUSH_SIZE_MIN || mBrushSize > BRUSH_SIZE_MAX) {
            mBrushSize = BRUSH_SIZE_DEFAULT;
        }
        return this;
    }

    /**
     * Normalize right after deserialization so a corrupted snapshot
     * never propagates an invalid brush size to the engine
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        normalize();
    }

    /**
     * @return the union bounding box of the stroke path in normalized
     * coordinates: [minX, minY, maxX, maxY], an empty stroke returns a box of
     * zeros (the callers treat an empty stroke as having no area)
     */
    public float[] getBoundingBox() {
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        for (int i = 0; i + 1 < mPoints.size(); i += 2) {
            float x = mPoints.get(i);
            float y = mPoints.get(i + 1);
            if (x < minX) {
                minX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) {
            // empty stroke, no point was ever added
            return new float[]{0f, 0f, 0f, 0f};
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    @Override
    public String toString() {
        return "EditorStroke{points=" + (mPoints.size() / 2)
                + ", brushSize=" + mBrushSize + "}";
    }
}
