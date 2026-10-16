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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.RGBLuminanceSource;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented tests asserting the effectiveness of the QR censoring blur
 * against an independent decoder, executing on an Android device.
 *
 * What is bounded: the censorship guarantee against ONE independent decoder
 * (ZXing MultiFormatReader with TRY_HARDER, QR-only hints). Nothing else:
 * these tests make NO claim about ML-based restoration attacks, future or
 * other decoder implementations, or human readability of the blurred pixels.
 * Do not read a green run as a stronger guarantee than that.
 *
 * ZXing is an independent test oracle only (androidTestImplementation), it is
 * never part of the shipped app.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class QrCensorEffectivenessTest {

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
    private static FaceEngine mFaceEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        mQrEngine = mMLEngineProvider.get(QREngine.class);
        // FaceEngine's blur methods touch no model (only detectFaceRaw and
        // isFaceSimilar do), this runs on a clean device without downloads
        mFaceEngine = mMLEngineProvider.get(FaceEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        mMLEngineProvider.dispose();
    }

    @Test
    public void qrCensor_blurMakesQrUndecodable() {
        // pair asset: both codes detected, both censored, nothing decodable
        Bitmap pair = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code_pair));
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
        Bitmap single = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code));
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
        Bitmap pair = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code_pair));
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
        Bitmap single = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code));
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
        Bitmap single = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code));
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
        Bitmap pristine = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code));
        int originalWidth = pristine.getWidth();
        int originalHeight = pristine.getHeight();
        Bitmap current = null;
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
                Bitmap downscaled = Bitmap.createScaledBitmap(pristine,
                        Math.max(1, originalWidth / divisor),
                        Math.max(1, originalHeight / divisor), true);
                current = Bitmap.createScaledBitmap(downscaled,
                        originalWidth, originalHeight, true);
                downscaled.recycle();
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
        Bitmap single = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code));
        Bitmap pair = BitmapFactory.decodeStream(mMLEngineProvider.getContext().getResources().
                openRawResource(R.raw.qr_test_code_pair));
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
