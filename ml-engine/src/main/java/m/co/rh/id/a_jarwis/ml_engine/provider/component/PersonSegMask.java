package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import org.opencv.core.Mat;

/**
 * Opaque handle of a native person mask created by {@link PersonSegEngine},
 * holding the single channel CV_8U alpha mask in the native memory.
 * Call {@link #release()} once the mask is not needed anymore.
 * A mask obtained through {@link PersonSegEngine#getMask} is owned by the
 * engine cache and must not be released by the caller.
 */
public class PersonSegMask {
    final Mat mMask;

    PersonSegMask(Mat mask) {
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
