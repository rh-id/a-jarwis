package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Stateful QR decoder of live camera frames. Converts a camera Y plane byte
 * array into a grayscale Mat honouring the plane rowStride, rotates it upright
 * and decodes it through {@link QREngine}, so no OpenCV type ever crosses the
 * module boundary.
 * The instance owns its reusable rotation Mat and must be released when the
 * owner no longer decodes frames (see {@link #release()}); a decode after a
 * release lazily reallocates it.
 * Not thread-safe: decode calls must be serialized on a single thread (the
 * camera analyzer executor), the reusable rotation Mat and the release state
 * assume serialized access.
 */
public class QrFrameDecoder {
    private static final String TAG = "QrFrameDecoder";

    private final ILogger mLogger;
    private final QREngine mQrEngine;
    /**
     * Preallocated rotation destination Mat, owned by this instance and grown
     * by Core.rotate on size change, reused across frames
     */
    private Mat mRotatedLuma;

    public QrFrameDecoder(Provider provider) {
        mLogger = provider.get(ILogger.class);
        // loads the OpenCV native library, idempotent (a no-op once loaded).
        // Doing it here eagerly prevents the cold-start race where the first
        // analyzer frames hit an UnsatisfiedLinkError while a provider-started
        // init is still in flight; this couples to nothing outside the
        // org.opencv API this module already depends on
        if (OpenCVLoader.initLocal()) {
            mLogger.d(TAG, "OpenCV loaded");
        } else {
            mLogger.e(TAG, "Error loading OpenCV");
        }
        mQrEngine = new QREngine(provider);
    }

    /**
     * Decode the QR codes of a camera luma frame. The plane bytes are copied
     * into a grayscale Mat honouring the given rowStride (frequently wider
     * than the image width, reading width bytes per row would skew the image),
     * rotated upright per the given rotation degrees, then decoded through the
     * QR engine.
     *
     * @param yPlane          luma plane bytes in the plane layout
     *                        (rowStride-wide rows), owned by the caller:
     *                        never mutated, only read
     * @param width           frame width in px
     * @param height          frame height in px
     * @param rowStride       byte length of one plane row, must be >= width
     * @param rotationDegrees rotation to apply so the frame is upright
     *                        (ImageProxy.getImageInfo().getRotationDegrees()),
     *                        one of 0/90/180/270
     * @return the decoded QR codes, empty when nothing decoded or the input is
     * invalid (null/empty plane, non-positive dimensions, rowStride < width)
     */
    public List<QREngine.QrDecodeResult> decode(byte[] yPlane, int width, int height,
                                                int rowStride, int rotationDegrees) {
        List<QREngine.QrDecodeResult> resultList = new ArrayList<>();
        if (yPlane == null || yPlane.length == 0 || width <= 0 || height <= 0
                || rowStride < width) {
            mLogger.d(TAG, "decode: invalid frame input, returning empty result");
            return resultList;
        }
        Mat luma = new Mat(height, width, CvType.CV_8UC1);
        try {
            for (int row = 0; row < height; row++) {
                int offset = row * rowStride;
                int readLength = Math.min(rowStride, yPlane.length - offset);
                if (readLength < width) {
                    // the plane is truncated, the remaining rows are left with
                    // the Mat's uninitialized data (cv::Mat does not zero on
                    // allocation, they hold no pixel data of the frame)
                    break;
                }
                luma.put(row, 0, yPlane, offset, width);
            }
            resultList.addAll(mQrEngine.decodeQrCodes(rotateUpright(luma, rotationDegrees)));
        } finally {
            // the luma Mat is a per-decode temporary, the rotation Mat is reused
            luma.release();
        }
        mLogger.d(TAG, "Decoded frame with " + resultList.size() + " QR code(s)");
        return resultList;
    }

    /**
     * Rotate the given luma Mat so it is upright before the detection, using
     * the rotation degrees reported by the camera for the current display
     * orientation. Returns the reusable rotation destination Mat, or the input
     * itself when no rotation is needed.
     */
    private Mat rotateUpright(Mat luma, int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                Core.rotate(luma, ensureRotatedLuma(), Core.ROTATE_90_CLOCKWISE);
                break;
            case 180:
                Core.rotate(luma, ensureRotatedLuma(), Core.ROTATE_180);
                break;
            case 270:
                Core.rotate(luma, ensureRotatedLuma(), Core.ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                // already upright
                return luma;
        }
        return mRotatedLuma;
    }

    private Mat ensureRotatedLuma() {
        if (mRotatedLuma == null) {
            mRotatedLuma = new Mat();
        }
        return mRotatedLuma;
    }

    /**
     * Decode the QR codes of the given bitmap through the QR engine, the
     * bitmap is neither mutated nor released by this decoder.
     *
     * @param bitmap image to scan (e.g. the scan-from-image fallback of the
     *               live scanner), decoded through {@link QREngine}
     * @return the decoded QR codes, empty when nothing decoded
     */
    public List<QREngine.QrDecodeResult> decodeBitmap(Bitmap bitmap) {
        Mat img = mQrEngine.bitmapToRgbMat(bitmap);
        try {
            return mQrEngine.decodeQrCodes(img);
        } finally {
            img.release();
        }
    }

    /**
     * Release the native memory owned by this decoder (the reusable rotation
     * Mat), safe to call multiple times. A decode after a release lazily
     * reallocates the rotation Mat.
     */
    public void release() {
        if (mRotatedLuma != null) {
            mRotatedLuma.release();
            mRotatedLuma = null;
        }
    }
}
