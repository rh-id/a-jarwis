package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test, which will execute on an Android device.
 * QREngine uses the in-box OpenCV QRCodeDetector, no downloaded model is
 * needed, getting the engine from the provider is all the setup required.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class QREngineTest {

    /**
     * Minimum QR rect side (px) for the single QR test asset,
     * the real code is much larger, a detection below this is a false positive
     */
    private static final int MIN_QR_SIDE_PX = 200;
    /**
     * QR payload of the single QR test asset (qr_test_code.png)
     */
    private static final String SINGLE_QR_PAYLOAD = "A-JARWIS-QR-TEST-0001";
    /**
     * QR payloads of the two-QR test asset (qr_test_code_pair.png)
     */
    private static final String QR_A_PAYLOAD = "A-JARWIS-QR-A";
    private static final String QR_B_PAYLOAD = "A-JARWIS-QR-B";

    private static QREngine mQrEngine;
    private static QrFrameDecoder mFrameDecoder;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        mQrEngine = mMLEngineProvider.get(QREngine.class);
        mFrameDecoder = new QrFrameDecoder(mMLEngineProvider);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        if (mFrameDecoder != null) {
            mFrameDecoder.release();
        }
        mMLEngineProvider.dispose();
    }

    @Test
    public void qrEngineDecodeSingle() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code));
        List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(bitmap);
        assertEquals(1, resultList.size());
        QREngine.QrDecodeResult result = resultList.get(0);
        assertEquals(SINGLE_QR_PAYLOAD, result.getText());
        assertRectInsideBounds(result.getRect(), bitmap);
    }

    @Test
    public void qrEngineDetectSingle() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code));
        List<Rect> rectList = mQrEngine.detectQrCodes(bitmap);
        assertEquals(1, rectList.size());
        Rect rect = rectList.get(0);
        assertTrue("Detected rect is too small to be the real QR code, got "
                        + rect.width() + "x" + rect.height(),
                rect.width() >= MIN_QR_SIDE_PX && rect.height() >= MIN_QR_SIDE_PX);
        assertRectInsideBounds(rect, bitmap);
    }

    @Test
    public void qrEngineDetectMulti() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code_pair));
        List<Rect> rectList = mQrEngine.detectQrCodes(bitmap);
        assertEquals(2, rectList.size());
        Rect rect1 = rectList.get(0);
        Rect rect2 = rectList.get(1);
        assertRectInsideBounds(rect1, bitmap);
        assertRectInsideBounds(rect2, bitmap);
        // the two codes sit side by side, a significant overlap means the quad
        // to bounds conversion or the point parsing is wrong
        Rect intersection = new Rect();
        long intersectionArea = intersection.setIntersect(rect1, rect2)
                ? (long) intersection.width() * intersection.height() : 0L;
        long rect1Area = (long) rect1.width() * rect1.height();
        long rect2Area = (long) rect2.width() * rect2.height();
        long minArea = Math.min(rect1Area, rect2Area);
        assertTrue("Detected QR rects overlap significantly, intersection "
                        + intersectionArea + " of the smaller rect area " + minArea,
                intersectionArea * 2 < minArea);
    }

    @Test
    public void qrEngineDecodeMulti() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code_pair));
        List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(bitmap);
        assertEquals(2, resultList.size());
        List<String> decodedTexts = new ArrayList<>();
        for (QREngine.QrDecodeResult result : resultList) {
            decodedTexts.add(result.getText());
        }
        // the engine does not guarantee an order, compare order-independently
        assertTrue(decodedTexts.contains(QR_A_PAYLOAD));
        assertTrue(decodedTexts.contains(QR_B_PAYLOAD));
    }

    @Test
    public void qrEngineDecodeMulti_matLumaInput() {
        Context context = mMLEngineProvider.getContext();
        Bitmap bitmap = BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code_pair));
        // convert to a CV_8UC1 grayscale Mat the same way the camera analyzer
        // feeds its Y plane, then decode through the Mat entry point
        Mat luma = new Mat();
        Utils.bitmapToMat(bitmap, luma);
        Imgproc.cvtColor(luma, luma, Imgproc.COLOR_RGBA2GRAY);
        List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(luma);
        // the caller owns the input Mat, this mirrors the analyzer ownership
        luma.release();
        assertEquals(2, resultList.size());
        List<String> decodedTexts = new ArrayList<>();
        for (QREngine.QrDecodeResult result : resultList) {
            decodedTexts.add(result.getText());
        }
        assertTrue(decodedTexts.contains(QR_A_PAYLOAD));
        assertTrue(decodedTexts.contains(QR_B_PAYLOAD));
    }

    @Test
    public void qrFrameDecoder_rowStrideGreaterThanWidth() {
        Bitmap bitmap = loadPairBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] compact = grayscaleBytes(bitmap);
        // padded plane: every row carries stride-width trailing bytes after the
        // pixel data that the decoder must skip, this is the camera reality
        // where the rowStride is frequently wider than the image width
        int rowStride = width + 64;
        byte[] padded = new byte[rowStride * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(compact, row * width, padded, row * rowStride, width);
        }
        List<String> texts = decodeTexts(mFrameDecoder, padded, width, height, rowStride, 0);
        assertEquals(2, texts.size());
        assertTrue(texts.contains(QR_A_PAYLOAD));
        assertTrue(texts.contains(QR_B_PAYLOAD));
    }

    @Test
    public void qrFrameDecoder_doesNotMutateCallerBuffer() {
        Bitmap bitmap = loadPairBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] compact = grayscaleBytes(bitmap);
        byte[] expected = compact.clone();
        List<String> first = decodeTexts(mFrameDecoder, compact, width, height, width, 0);
        assertEquals(2, first.size());
        // repeated calls on the same buffer keep working
        List<String> second = decodeTexts(mFrameDecoder, compact, width, height, width, 0);
        assertEquals(2, second.size());
        assertTrue("the decoder must not mutate the caller's plane bytes",
                Arrays.equals(expected, compact));
    }

    @Test
    public void qrFrameDecoder_rotatedInput() {
        Bitmap bitmap = loadPairBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // reference: the upright plane decoded without rotation, the rotated
        // decodes must land each payload's rect on the same position (within a
        // small resampling tolerance). This is what pins the rotation mapping:
        // a swapped CW/CCW compensation produces a net 180 degrees rotation,
        // QR codes still decode at 180 degrees (the payload assertions below
        // keep passing) and, because the two codes of the asset are placed
        // symmetrically, each rect even lands near A rect position - the
        // payload-to-rect pairing per code is the only thing that breaks.
        byte[] upright = grayscaleBytes(bitmap);
        List<QREngine.QrDecodeResult> uprightResults =
                mFrameDecoder.decode(upright, width, height, width, 0);
        assertEquals(2, uprightResults.size());
        int tolerance = (int) Math.ceil(Math.max(width, height) * 0.02);
        int rotatedWidth = height;
        int rotatedHeight = width;
        // input pre-rotated 90 degrees counter-clockwise, the decoder's 90
        // degrees clockwise rotation must restore it upright
        byte[] rotatedCcw = rotatedGrayscaleBytes(bitmap, Core.ROTATE_90_COUNTERCLOCKWISE);
        List<QREngine.QrDecodeResult> results90 = mFrameDecoder.decode(rotatedCcw,
                rotatedWidth, rotatedHeight, rotatedWidth, 90);
        assertEquals(2, results90.size());
        List<String> texts90 = textsOf(results90);
        assertTrue(texts90.contains(QR_A_PAYLOAD));
        assertTrue(texts90.contains(QR_B_PAYLOAD));
        assertResultsMatchPerPayload(uprightResults, results90, tolerance);
        // input pre-rotated 180 degrees, the decoder's 180 degrees rotation
        // must restore it upright
        byte[] rotated180 = rotatedGrayscaleBytes(bitmap, Core.ROTATE_180);
        List<QREngine.QrDecodeResult> results180 = mFrameDecoder.decode(rotated180,
                width, height, width, 180);
        assertEquals(2, results180.size());
        List<String> texts180 = textsOf(results180);
        assertTrue(texts180.contains(QR_A_PAYLOAD));
        assertTrue(texts180.contains(QR_B_PAYLOAD));
        assertResultsMatchPerPayload(uprightResults, results180, tolerance);
        // input pre-rotated 90 degrees clockwise, the decoder's 270 degrees
        // (90 counter-clockwise) rotation must restore it upright
        byte[] rotatedCw = rotatedGrayscaleBytes(bitmap, Core.ROTATE_90_CLOCKWISE);
        List<QREngine.QrDecodeResult> results270 = mFrameDecoder.decode(rotatedCw,
                rotatedWidth, rotatedHeight, rotatedWidth, 270);
        assertEquals(2, results270.size());
        List<String> texts270 = textsOf(results270);
        assertTrue(texts270.contains(QR_A_PAYLOAD));
        assertTrue(texts270.contains(QR_B_PAYLOAD));
        assertResultsMatchPerPayload(uprightResults, results270, tolerance);
    }

    @Test
    public void qrFrameDecoder_invalidInputReturnsEmpty() {
        Bitmap bitmap = loadPairBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        assertTrue(mFrameDecoder.decode(null, width, height, width, 0).isEmpty());
        assertTrue(mFrameDecoder.decode(new byte[0], width, height, width, 0).isEmpty());
        assertTrue(mFrameDecoder.decode(new byte[0], 0, 0, 0, 0).isEmpty());
        // the dimensions are far larger than the plane holds, every row is
        // truncated and left with the Mat's uninitialized data, nothing
        // decodes and it does not throw
        assertTrue(mFrameDecoder.decode(new byte[100], 200, 200, 200, 0).isEmpty());
    }

    @Test
    public void qrFrameDecoder_releaseIsIdempotent() {
        QrFrameDecoder decoder = new QrFrameDecoder(mMLEngineProvider);
        Bitmap bitmap = loadPairBitmap();
        List<String> texts = decodeTexts(decoder, grayscaleBytes(bitmap),
                bitmap.getWidth(), bitmap.getHeight(), bitmap.getWidth(), 0);
        assertEquals(2, texts.size());
        // releasing twice must be safe, the native rotation Mat is freed once
        decoder.release();
        decoder.release();
    }

    private static Bitmap loadPairBitmap() {
        Context context = mMLEngineProvider.getContext();
        return BitmapFactory.decodeStream(context.getResources().
                openRawResource(R.raw.qr_test_code_pair));
    }

    /**
     * @return the grayscale bytes of the given bitmap in row-major layout
     * (rowStride = width), the helper Mats are released on every path
     */
    private static byte[] grayscaleBytes(Bitmap bitmap) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            byte[] bytes = new byte[(int) gray.total()];
            gray.get(0, 0, bytes);
            return bytes;
        } finally {
            rgba.release();
            gray.release();
        }
    }

    /**
     * @return the grayscale bytes of the given bitmap rotated by the given
     * Core.rotate code, in row-major layout (rowStride = width)
     */
    private static byte[] rotatedGrayscaleBytes(Bitmap bitmap, int coreRotateCode) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat rotated = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Core.rotate(gray, rotated, coreRotateCode);
            byte[] bytes = new byte[(int) rotated.total()];
            rotated.get(0, 0, bytes);
            return bytes;
        } finally {
            rgba.release();
            gray.release();
            rotated.release();
        }
    }

    private static List<String> decodeTexts(QrFrameDecoder decoder, byte[] yPlane,
                                            int width, int height, int rowStride,
                                            int rotationDegrees) {
        return textsOf(decoder.decode(yPlane, width, height, rowStride, rotationDegrees));
    }

    private static List<String> textsOf(List<QREngine.QrDecodeResult> resultList) {
        List<String> texts = new ArrayList<>();
        for (QREngine.QrDecodeResult result : resultList) {
            texts.add(result.getText());
        }
        return texts;
    }

    /**
     * Assert the actual results match the reference results payload-by-payload
     * (order-independent), each rect within the given center/size tolerance of
     * its reference, allowing the resampling shift of a pre-rotation and
     * re-rotation round trip. Matching by payload is what pins the rotation
     * mapping: a swapped CW/CCW compensation produces a net 180 degrees
     * rotation, the codes still decode (the payload assertions keep passing)
     * but each payload's rect ends up at the mirrored position of the other
     * code, which this comparison flags.
     */
    private static void assertResultsMatchPerPayload(List<QREngine.QrDecodeResult> expected,
                                                     List<QREngine.QrDecodeResult> actual,
                                                     int tolerance) {
        assertEquals(expected.size(), actual.size());
        for (QREngine.QrDecodeResult expectedItem : expected) {
            QREngine.QrDecodeResult actualItem = null;
            for (QREngine.QrDecodeResult candidate : actual) {
                if (expectedItem.getText().equals(candidate.getText())) {
                    actualItem = candidate;
                    break;
                }
            }
            assertNotNull("no decoded result for payload " + expectedItem.getText(),
                    actualItem);
            Rect expectedRect = expectedItem.getRect();
            Rect actualRect = actualItem.getRect();
            assertTrue("rect center moved by more than the tolerance for payload "
                            + expectedItem.getText() + ", expected " + expectedRect
                            + " actual " + actualRect,
                    Math.abs(expectedRect.centerX() - actualRect.centerX()) <= tolerance
                            && Math.abs(expectedRect.centerY() - actualRect.centerY()) <= tolerance);
            assertTrue("rect size changed by more than the tolerance for payload "
                            + expectedItem.getText() + ", expected " + expectedRect
                            + " actual " + actualRect,
                    Math.abs(expectedRect.width() - actualRect.width()) <= tolerance
                            && Math.abs(expectedRect.height() - actualRect.height()) <= tolerance);
        }
    }

    /**
     * Assert the given QR rect is non-degenerate and fully inside the
     * given bitmap bounds
     */
    private static void assertRectInsideBounds(Rect rect, Bitmap bitmap) {
        assertTrue(rect.width() > 0);
        assertTrue(rect.height() > 0);
        assertTrue(rect.left >= 0);
        assertTrue(rect.top >= 0);
        assertTrue(rect.right <= bitmap.getWidth());
        assertTrue(rect.bottom <= bitmap.getHeight());
    }
}
