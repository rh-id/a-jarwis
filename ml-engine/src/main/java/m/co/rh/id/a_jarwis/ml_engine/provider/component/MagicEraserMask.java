package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import org.opencv.core.Mat;

/**
 * Opaque handle of a native object mask created by {@link MagicEraserEngine},
 * holding the single channel CV_8U alpha mask in the native memory so the
 * :app editor never imports org.opencv.
 * Call {@link #release()} once the mask is not needed anymore.
 */
public class MagicEraserMask {
    final Mat mMask;

    MagicEraserMask(Mat mask) {
        mMask = mask;
    }

    /**
     * Release the underlying native mask. A released handle must not be used
     * anymore, the native memory returns to the OpenCV allocator.
     */
    public void release() {
        mMask.release();
    }
}
