package m.co.rh.id.a_jarwis.ml_engine.model;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * Configuration of a face blur: the feathered shape of the blurred area
 * and the blur strength.
 * Only holds primitive fields so it is safe to keep inside the serializable
 * editor state, never hold Bitmap/Rect/Uri here.
 */
public class BlurConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Feathered ellipse blur shape (default)
     */
    public static final int SHAPE_ELLIPSE = 0;
    /**
     * Feathered rounded square blur shape
     */
    public static final int SHAPE_SQUARE = 1;

    /**
     * Minimum blur strength
     */
    public static final int STRENGTH_MIN = 1;
    /**
     * Maximum blur strength
     */
    public static final int STRENGTH_MAX = 5;
    /**
     * Default blur strength
     */
    public static final int STRENGTH_DEFAULT = 3;

    private int mShape;
    private int mStrength;

    public BlurConfig() {
        mShape = SHAPE_ELLIPSE;
        mStrength = STRENGTH_DEFAULT;
    }

    public BlurConfig(int shape, int strength) {
        mShape = shape;
        mStrength = strength;
        normalize();
    }

    /**
     * Copy constructor, each state holder must own its own config instance
     */
    public BlurConfig(BlurConfig other) {
        mShape = other.mShape;
        mStrength = other.mStrength;
    }

    public int getShape() {
        return mShape;
    }

    public void setShape(int shape) {
        mShape = shape;
    }

    public int getStrength() {
        return mStrength;
    }

    public void setStrength(int strength) {
        mStrength = strength;
    }

    /**
     * Clamp the fields to the supported values, called after deserialization
     * (and on construction) so a corrupted or legacy snapshot without a config
     * never propagates an invalid shape/strength to the engine
     *
     * @return this
     */
    public BlurConfig normalize() {
        if (mShape != SHAPE_ELLIPSE && mShape != SHAPE_SQUARE) {
            mShape = SHAPE_ELLIPSE;
        }
        if (mStrength < STRENGTH_MIN || mStrength > STRENGTH_MAX) {
            mStrength = STRENGTH_DEFAULT;
        }
        return this;
    }

    /**
     * Normalize right after deserialization so a corrupted or legacy snapshot
     * never propagates an invalid shape/strength to the engine
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        normalize();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlurConfig)) {
            return false;
        }
        BlurConfig other = (BlurConfig) o;
        return mShape == other.mShape && mStrength == other.mStrength;
    }

    @Override
    public int hashCode() {
        return 31 * mShape + mStrength;
    }

    @Override
    public String toString() {
        return "BlurConfig{shape=" + mShape + ", strength=" + mStrength + "}";
    }
}
