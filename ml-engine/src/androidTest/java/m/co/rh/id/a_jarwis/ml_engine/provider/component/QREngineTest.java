package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import androidx.annotation.RawRes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test, which will execute on an Android device.
 * QREngine uses the in-box OpenCV QRCodeDetector, no downloaded model is
 * needed, getting the engine from the provider is all the setup required.
 * <p>
 * The qrCensor_* tests (merged from the former QrCensorEffectivenessTest,
 * the single test class per engine component) assert the effectiveness of
 * the QR censoring blur against an independent decoder.
 * <p>
 * What is bounded: the censorship guarantee against ONE independent decoder
 * (ZXing MultiFormatReader with TRY_HARDER, QR-only hints). Nothing else:
 * these tests make NO claim about ML-based restoration attacks, future or
 * other decoder implementations, or human readability of the blurred pixels.
 * Do not read a green run as a stronger guarantee than that.
 * <p>
 * ZXing is an independent test oracle only (androidTestImplementation), it is
 * never part of the shipped app.
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

    /**
     * The blur configuration the QR censor editor seeds for every detected
     * code (QrCensorEditorPage seeds mLastUsedBlurConfig with exactly this
     * value in provideComponent). This test is the regression guard keeping
     * the seed and the censoring guarantee in sync: if either side drifts,
     * these tests fail.
     */
    private static final BlurConfig QR_PAGE_SEED_CONFIG =
            new BlurConfig(BlurConfig.SHAPE_SQUARE, BlurConfig.STRENGTH_MAX);
    /**
     * Deterministic cap of the degradation loop in
     * qrCensor_undetectableImpliesUndecodable, each pass halves the resolution
     * and restores the original dimensions
     */
    private static final int MAX_DEGRADE_ATTEMPTS = 6;

    private static QREngine mQrEngine;
    private static QrFrameDecoder mFrameDecoder;
    private static FaceEngine mFaceEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        mQrEngine = mMLEngineProvider.get(QREngine.class);
        mFrameDecoder = new QrFrameDecoder(mMLEngineProvider);
        // the merged qrCensor_* tests (former QrCensorEffectivenessTest) also
        // drive the censoring blur through FaceEngine: its blur methods touch
        // no model (only detectFaceRaw and isFaceSimilar do), and QREngine
        // itself uses the in-box OpenCV QRCodeDetector, so this class needs
        // no ModelTestHelper.installModels call at all — it runs on a clean
        // device without downloads
        mFaceEngine = mMLEngineProvider.get(FaceEngine.class);
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
        Bitmap bitmap = null;
        try {
            bitmap = decodeRaw(R.raw.qr_test_code);
            List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(bitmap);
            assertEquals(1, resultList.size());
            QREngine.QrDecodeResult result = resultList.get(0);
            assertEquals(SINGLE_QR_PAYLOAD, result.getText());
            assertRectInsideBounds(result.getRect(), bitmap);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrEngineDetectSingle() {
        Bitmap bitmap = null;
        try {
            bitmap = decodeRaw(R.raw.qr_test_code);
            List<Rect> rectList = mQrEngine.detectQrCodes(bitmap);
            assertEquals(1, rectList.size());
            Rect rect = rectList.get(0);
            assertTrue("Detected rect is too small to be the real QR code, got "
                            + rect.width() + "x" + rect.height(),
                    rect.width() >= MIN_QR_SIDE_PX && rect.height() >= MIN_QR_SIDE_PX);
            assertRectInsideBounds(rect, bitmap);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrEngineDetectMulti() {
        Bitmap bitmap = null;
        try {
            bitmap = decodeRaw(R.raw.qr_test_code_pair);
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
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrEngineDecodeMulti() {
        Bitmap bitmap = null;
        try {
            bitmap = decodeRaw(R.raw.qr_test_code_pair);
            List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(bitmap);
            assertEquals(2, resultList.size());
            List<String> decodedTexts = new ArrayList<>();
            for (QREngine.QrDecodeResult result : resultList) {
                decodedTexts.add(result.getText());
            }
            // the engine does not guarantee an order, compare order-independently
            assertTrue(decodedTexts.contains(QR_A_PAYLOAD));
            assertTrue(decodedTexts.contains(QR_B_PAYLOAD));
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrEngineDecodeMulti_matLumaInput() {
        Bitmap bitmap = null;
        Mat luma = null;
        try {
            bitmap = decodeRaw(R.raw.qr_test_code_pair);
            // convert to a CV_8UC1 grayscale Mat the same way the camera analyzer
            // feeds its Y plane, then decode through the Mat entry point
            luma = new Mat();
            Utils.bitmapToMat(bitmap, luma);
            Imgproc.cvtColor(luma, luma, Imgproc.COLOR_RGBA2GRAY);
            List<QREngine.QrDecodeResult> resultList = mQrEngine.decodeQrCodes(luma);
            assertEquals(2, resultList.size());
            List<String> decodedTexts = new ArrayList<>();
            for (QREngine.QrDecodeResult result : resultList) {
                decodedTexts.add(result.getText());
            }
            assertTrue(decodedTexts.contains(QR_A_PAYLOAD));
            assertTrue(decodedTexts.contains(QR_B_PAYLOAD));
        } finally {
            // the caller owns the input Mat, this mirrors the analyzer ownership
            if (luma != null) {
                luma.release();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrFrameDecoder_rowStrideGreaterThanWidth() {
        Bitmap bitmap = null;
        try {
            bitmap = loadPairBitmap();
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
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrFrameDecoder_doesNotMutateCallerBuffer() {
        Bitmap bitmap = null;
        try {
            bitmap = loadPairBitmap();
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
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrFrameDecoder_rotatedInput() {
        Bitmap bitmap = null;
        try {
            bitmap = loadPairBitmap();
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
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrFrameDecoder_invalidInputReturnsEmpty() {
        Bitmap bitmap = null;
        try {
            bitmap = loadPairBitmap();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            assertTrue(mFrameDecoder.decode(null, width, height, width, 0).isEmpty());
            assertTrue(mFrameDecoder.decode(new byte[0], width, height, width, 0).isEmpty());
            assertTrue(mFrameDecoder.decode(new byte[0], 0, 0, 0, 0).isEmpty());
            // the dimensions are far larger than the plane holds, every row is
            // truncated and left with the Mat's uninitialized data, nothing
            // decodes and it does not throw
            assertTrue(mFrameDecoder.decode(new byte[100], 200, 200, 200, 0).isEmpty());
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrFrameDecoder_releaseIsIdempotent() {
        QrFrameDecoder decoder = new QrFrameDecoder(mMLEngineProvider);
        Bitmap bitmap = null;
        try {
            bitmap = loadPairBitmap();
            List<String> texts = decodeTexts(decoder, grayscaleBytes(bitmap),
                    bitmap.getWidth(), bitmap.getHeight(), bitmap.getWidth(), 0);
            assertEquals(2, texts.size());
            // releasing twice must be safe, the native rotation Mat is freed once
            decoder.release();
            decoder.release();
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    @Test
    public void qrCensor_blurMakesQrUndecodable() {
        // pair asset: both codes detected, both censored, nothing decodable
        Bitmap pair = decodeRaw(R.raw.qr_test_code_pair);
        try {
            List<Rect> pairRects = mQrEngine.detectQrCodes(pair);
            assertEquals(2, pairRects.size());
            Bitmap blurredPair = mFaceEngine.blurFacesAt(pair, pairRects, QR_PAGE_SEED_CONFIG);
            try {
                assertTrue("the blurred pair asset must not decode",
                        mQrEngine.decodeQrCodes(blurredPair).isEmpty());
            } finally {
                blurredPair.recycle();
            }
        } finally {
            pair.recycle();
        }
        // single asset: same censoring, same expectation
        Bitmap single = decodeRaw(R.raw.qr_test_code);
        try {
            List<Rect> singleRects = mQrEngine.detectQrCodes(single);
            assertEquals(1, singleRects.size());
            Bitmap blurredSingle = mFaceEngine.blurFacesAt(single, singleRects,
                    QR_PAGE_SEED_CONFIG);
            try {
                assertTrue("the blurred single asset must not decode",
                        mQrEngine.decodeQrCodes(blurredSingle).isEmpty());
            } finally {
                blurredSingle.recycle();
            }
        } finally {
            single.recycle();
        }
    }

    @Test
    public void qrCensor_defaultSeedIsUndecodable() {
        // regression guard on the exact configuration QrCensorEditorPage seeds
        // (see QR_PAGE_SEED_CONFIG), asserted on both assets so a weakened
        // default or a drifted seed cannot ship silently
        Bitmap pair = decodeRaw(R.raw.qr_test_code_pair);
        try {
            List<Rect> pairRects = mQrEngine.detectQrCodes(pair);
            assertEquals(2, pairRects.size());
            Bitmap blurredPair = mFaceEngine.blurFacesAt(pair, pairRects, QR_PAGE_SEED_CONFIG);
            try {
                assertTrue(mQrEngine.decodeQrCodes(blurredPair).isEmpty());
            } finally {
                blurredPair.recycle();
            }
        } finally {
            pair.recycle();
        }
        Bitmap single = decodeRaw(R.raw.qr_test_code);
        try {
            List<Rect> singleRects = mQrEngine.detectQrCodes(single);
            assertEquals(1, singleRects.size());
            Bitmap blurredSingle = mFaceEngine.blurFacesAt(single, singleRects,
                    QR_PAGE_SEED_CONFIG);
            try {
                assertTrue(mQrEngine.decodeQrCodes(blurredSingle).isEmpty());
            } finally {
                blurredSingle.recycle();
            }
        } finally {
            single.recycle();
        }
    }

    @Test
    public void qrCensor_largeCodeEllipseStillUndecodable() {
        // the test that catches a weakened blur kernel: even the weakest
        // reachable setting on a 4x-upscaled code must decimate the payload.
        // Detection is deliberately unconstrained: empirically OpenCV still
        // detects (not decodes) a code here, that is reported diagnostically
        // in the assertion message and must never become a requirement.
        Bitmap single = decodeRaw(R.raw.qr_test_code);
        Bitmap scaled = null;
        Bitmap blurred = null;
        try {
            scaled = Bitmap.createScaledBitmap(single,
                    single.getWidth() * 4, single.getHeight() * 4, true);
            List<Rect> rects = mQrEngine.detectQrCodes(scaled);
            assertEquals(1, rects.size());
            blurred = mFaceEngine.blurFacesAt(scaled, rects,
                    new BlurConfig(BlurConfig.SHAPE_ELLIPSE, BlurConfig.STRENGTH_MIN));
            int postBlurDetectionCount = mQrEngine.detectQrCodes(blurred).size();
            List<QREngine.QrDecodeResult> postBlurDecoded = mQrEngine.decodeQrCodes(blurred);
            assertTrue("post-blur decode must be empty (diagnostic: post-blur "
                            + "detection still finds " + postBlurDetectionCount
                            + " QR code(s), which is unconstrained)",
                    postBlurDecoded.isEmpty());
        } finally {
            if (blurred != null) {
                blurred.recycle();
            }
            if (scaled != null) {
                scaled.recycle();
            }
            single.recycle();
        }
    }

    @Test
    public void qrCensor_undetectableImpliesUndecodable() {
        // pins the invariant "our censor's blind spot is our decoder's blind
        // spot": once OpenCV detection gives up, its decode must give up too.
        // It trips if anyone swaps in a stronger decode backend while keeping
        // OpenCV detection.
        Bitmap pristine = decodeRaw(R.raw.qr_test_code);
        int originalWidth = pristine.getWidth();
        int originalHeight = pristine.getHeight();
        Bitmap current = null;
        Bitmap downscaled = null;
        // cumulative downscale divisor, each pass quarters the resolution
        // (from the pristine asset, so the damage accumulates deterministically
        // instead of re-degrading the same intermediate)
        int divisor = 1;
        int attempts = 0;
        try {
            while (attempts < MAX_DEGRADE_ATTEMPTS) {
                divisor *= 4;
                attempts++;
                if (current != null) {
                    current.recycle();
                }
                if (downscaled != null) {
                    downscaled.recycle();
                }
                downscaled = Bitmap.createScaledBitmap(pristine,
                        Math.max(1, originalWidth / divisor),
                        Math.max(1, originalHeight / divisor), true);
                current = Bitmap.createScaledBitmap(downscaled,
                        originalWidth, originalHeight, true);
                if (mQrEngine.detectQrCodes(current).isEmpty()) {
                    break;
                }
            }
            assertTrue("failed to degrade the single asset below the detector's "
                            + "sensitivity within " + MAX_DEGRADE_ATTEMPTS
                            + " passes (detection still finds "
                            + mQrEngine.detectQrCodes(current).size() + " QR code(s))",
                    mQrEngine.detectQrCodes(current).isEmpty());
            assertTrue("detection-0 must imply decode-0 on the same bitmap",
                    mQrEngine.decodeQrCodes(current).isEmpty());
        } finally {
            if (downscaled != null) {
                downscaled.recycle();
            }
            if (current != null) {
                current.recycle();
            }
            pristine.recycle();
        }
    }

    @Test
    public void qrCensor_adversarialZxingUndecodable() {
        // positive controls first, always: if ZXing cannot read the pristine
        // assets the oracle setup is broken and every negative assertion below
        // would be meaningless
        Bitmap single = decodeRaw(R.raw.qr_test_code);
        Bitmap pair = decodeRaw(R.raw.qr_test_code_pair);
        try {
            List<String> singleTexts = readWithZxing(single);
            assertEquals(1, singleTexts.size());
            assertEquals(SINGLE_QR_PAYLOAD, singleTexts.get(0));
            List<String> pairTexts = readWithZxing(pair);
            assertEquals(2, pairTexts.size());
            assertTrue(pairTexts.contains(QR_A_PAYLOAD));
            assertTrue(pairTexts.contains(QR_B_PAYLOAD));

            // (a) pair censored with the shipped seed configuration
            List<Rect> pairRects = mQrEngine.detectQrCodes(pair);
            assertEquals(2, pairRects.size());
            Bitmap blurredPair = mFaceEngine.blurFacesAt(pair, pairRects, QR_PAGE_SEED_CONFIG);
            try {
                assertTrue("ZXing must not read any payload from the censored "
                        + "pair asset", readWithZxing(blurredPair).isEmpty());
            } finally {
                blurredPair.recycle();
            }

            // (b) 4x-upscaled single censored with the weakest reachable setting
            Bitmap scaled = Bitmap.createScaledBitmap(single,
                    single.getWidth() * 4, single.getHeight() * 4, true);
            try {
                List<Rect> rects = mQrEngine.detectQrCodes(scaled);
                assertEquals(1, rects.size());
                Bitmap blurred = mFaceEngine.blurFacesAt(scaled, rects,
                        new BlurConfig(BlurConfig.SHAPE_ELLIPSE, BlurConfig.STRENGTH_MIN));
                try {
                    assertTrue("ZXing must not read any payload from the weakened "
                            + "censor of the upscaled asset", readWithZxing(blurred).isEmpty());
                } finally {
                    blurred.recycle();
                }
            } finally {
                scaled.recycle();
            }
        } finally {
            single.recycle();
            pair.recycle();
        }
    }

    /**
     * Decodes a raw test image resource, closing the resource stream
     * (decodeStream does not close it, and the leaked fds showed up as
     * "A resource failed to call close." warnings on the CI emulator).
     */
    private static Bitmap decodeRaw(@RawRes int resId) {
        try (InputStream in = mMLEngineProvider.getContext().getResources().
                openRawResource(resId)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode raw resource", e);
        }
    }

    private static Bitmap loadPairBitmap() {
        return decodeRaw(R.raw.qr_test_code_pair);
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

    /**
     * Independent decoding oracle: read all QR payloads of the given bitmap
     * with ZXing's MultiFormatReader (TRY_HARDER, QR_CODE only, no
     * PURE_BARCODE: the assets are photographic renders, not clean
     * monochrome), wrapped in GenericMultipleBarcodeReader so multi-code
     * frames are enumerated. Rotation is not handled, QR decoding is
     * rotation-invariant.
     *
     * @return the decoded texts, empty when ZXing reads nothing
     */
    private static List<String> readWithZxing(Bitmap bitmap) {
        List<String> texts = new ArrayList<>();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        BinaryBitmap binaryBitmap = new BinaryBitmap(
                new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        try {
            Map<DecodeHintType, Object> hints = new Hashtable<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE));
            MultiFormatReader reader = new MultiFormatReader();
            reader.setHints(hints);
            GenericMultipleBarcodeReader multiReader =
                    new GenericMultipleBarcodeReader(reader);
            Result[] results = multiReader.decodeMultiple(binaryBitmap, hints);
            for (Result result : results) {
                texts.add(result.getText());
            }
        } catch (NotFoundException e) {
            // ZXing signals "nothing readable" with NotFoundException
        }
        return texts;
    }
}
