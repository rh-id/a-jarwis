package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;
import android.graphics.Rect;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeDetector;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class QREngine {
    private static final String TAG = "QREngine";

    private final ILogger mLogger;

    public QREngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
    }

    /**
     * Process bitmap using the QR code detector and return a list of rectangle
     * indicating the location of the detected QR codes.
     * Detection is used instead of decoding so the codes that fail to decode
     * are still reported and can be censored.
     */
    public List<Rect> detectQrCodes(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        // the detector holds no model state, a fresh instance per call keeps the
        // native state strictly call-local (thread safe without locking)
        QRCodeDetector qrCodeDetector = new QRCodeDetector();
        Mat img = bitmapToRgbMat(bitmap);
        Mat points = new Mat();
        try {
            boolean detected = qrCodeDetector.detectMulti(img, points);
            if (detected) {
                ArrayList<float[]> pointList = readPoints(points);
                int totalQr = pointList.size() / 4;
                for (int i = 0; i < totalQr; i++) {
                    Rect rect = quadToBoundsRect(pointList, i,
                            bitmap.getWidth(), bitmap.getHeight());
                    if (rect != null) {
                        rectList.add(rect);
                    }
                }
            }
        } finally {
            // the Mats hold native memory (the detect decode can reach several
            // thousand pixels), release them deterministically instead of
            // waiting for the finalizers
            img.release();
            points.release();
        }
        mLogger.d(TAG, "Detected " + rectList.size() + " QR code(s)");
        return rectList;
    }

    /**
     * Process bitmap using the QR code detector and decoder, return the decoded
     * text and location of each QR code that successfully decoded.
     * Detected codes with a null/empty decoded text are skipped.
     */
    public List<QrDecodeResult> decodeQrCodes(Bitmap bitmap) {
        // the RGB Mat is an internal temporary owned by this method
        Mat img = bitmapToRgbMat(bitmap);
        try {
            return decodeQrCodes(img);
        } finally {
            img.release();
        }
    }

    /**
     * Process a luma image Mat using the QR code detector and decoder, return
     * the decoded text and location of each QR code that successfully decoded.
     * Detected codes with a null/empty decoded text are skipped.
     * Accepts a CV_8UC1 (grayscale, e.g. a camera Y plane) or a CV_8UC3 (RGB)
     * Mat, any other Mat type returns an empty list.
     * Engine-internal (package) entry point, kept out of the public API as
     * hygiene. Note the visibility does NOT shield callers from the OpenCV
     * classpath requirement: the overloads of this class are only callable
     * from modules that themselves declare the OpenCV dependency (a
     * cross-module caller of the public Bitmap overload fails with
     * "cannot access org.opencv.core.Mat" whether this overload is private,
     * package-private or absent). App-module code must go through
     * {@link QrFrameDecoder}, the Mat-free camera frame path.
     *
     * @param luma image to scan, the caller owns this Mat: it must stay valid
     *             (not released) for the duration of the call. This method never
     *             releases it and never mutates it, only its internal
     *             temporaries are released
     * @return the decoded QR codes, empty when nothing decoded or the given Mat
     * type is unsupported
     */
    List<QrDecodeResult> decodeQrCodes(Mat luma) {
        List<QrDecodeResult> resultList = new ArrayList<>();
        if (luma == null || (luma.type() != CvType.CV_8UC1
                && luma.type() != CvType.CV_8UC3)) {
            mLogger.e(TAG, "decodeQrCodes: unsupported or null Mat input");
            return resultList;
        }
        QRCodeDetector qrCodeDetector = new QRCodeDetector();
        Mat points = new Mat();
        List<String> decodedInfo = new ArrayList<>();
        try {
            boolean detected = qrCodeDetector.detectAndDecodeMulti(luma, decodedInfo, points);
            if (detected) {
                ArrayList<float[]> pointList = readPoints(points);
                int totalQr = Math.min(decodedInfo.size(), pointList.size() / 4);
                for (int i = 0; i < totalQr; i++) {
                    String text = decodedInfo.get(i);
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    Rect rect = quadToBoundsRect(pointList, i,
                            luma.cols(), luma.rows());
                    if (rect != null) {
                        resultList.add(new QrDecodeResult(rect, text));
                    }
                }
            }
        } finally {
            // points is an internal temporary, the caller-owned input Mat is
            // never touched here
            points.release();
        }
        mLogger.d(TAG, "Decoded " + resultList.size() + " QR code(s)");
        return resultList;
    }

    /**
     * Convert the given bitmap into an RGB Mat for the package-private decode
     * entry point, the caller owns the returned Mat and must release it after
     * the decode. Engine-internal (package) helper shared with
     * {@link QrFrameDecoder}.
     */
    Mat bitmapToRgbMat(Bitmap bitmap) {
        Mat img = new Mat();
        Utils.bitmapToMat(bitmap, img);
        Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2RGB);
        return img;
    }    /**
     * Read all the CV_32FC2 points of the given points Mat into a flat list.
     * The detector output shape differs between OpenCV versions (a single row
     * of N points or a row of 4 corner points per QR code), reading the Mat
     * sequentially makes both shapes work: every 4 consecutive points belong
     * to one QR code.
     */
    private static ArrayList<float[]> readPoints(Mat points) {
        ArrayList<float[]> pointList = new ArrayList<>();
        int rows = points.rows();
        int cols = points.cols();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double[] xy = points.get(row, col);
                if (xy != null && xy.length >= 2) {
                    pointList.add(new float[]{(float) xy[0], (float) xy[1]});
                }
            }
        }
        return pointList;
    }

    /**
     * Convert the 4 corner points of the QR code at the given quad index into
     * its bounding rect, clamped to the given bitmap bounds.
     *
     * @return the bounding rect or null when the quad is degenerate
     * (empty after the clamping)
     */
    private static Rect quadToBoundsRect(ArrayList<float[]> pointList, int quadIndex,
                                         int bitmapWidth, int bitmapHeight) {
        int baseIndex = quadIndex * 4;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = baseIndex; i < baseIndex + 4 && i < pointList.size(); i++) {
            float[] xy = pointList.get(i);
            if (xy[0] < minX) {
                minX = xy[0];
            }
            if (xy[0] > maxX) {
                maxX = xy[0];
            }
            if (xy[1] < minY) {
                minY = xy[1];
            }
            if (xy[1] > maxY) {
                maxY = xy[1];
            }
        }
        int left = Math.max(0, Math.round(minX));
        int top = Math.max(0, Math.round(minY));
        int right = Math.min(bitmapWidth, Math.round(maxX));
        int bottom = Math.min(bitmapHeight, Math.round(maxY));
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    /**
     * Decoded QR code result, the location is in the scanned bitmap coordinate
     */
    public static class QrDecodeResult {
        private final Rect mRect;
        private final String mText;

        private QrDecodeResult(Rect rect, String text) {
            mRect = rect;
            mText = text;
        }

        public Rect getRect() {
            return mRect;
        }

        public String getText() {
            return mText;
        }
    }
}
