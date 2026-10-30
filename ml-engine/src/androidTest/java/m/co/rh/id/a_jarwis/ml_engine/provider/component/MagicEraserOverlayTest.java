package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Locale;

import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.aprovider.Provider;

/**
 * Regression tests for the magic eraser overlay plumbing. Pure
 * OpenCV/Bitmap operations on synthetic masks: no models, no SAM inference
 * (fast and deterministic, safe on the 32-bit emulator).
 * <p>
 * overlayConversion_isPremultipliedValid guards the premultiplied invariant
 * of the overlay conversion: Android ARGB_8888 bitmaps are premultiplied by
 * default and Utils.matToBitmap copies the Mat bytes straight into that
 * premultiplied storage, so building the overlay by merging constant white
 * (RGB=255 under alpha=0) produces invalid premultiplied data that the
 * framework composites as a full-white draw rect — the all-white overlay
 * regression. The conversion scales a clone of the mask to the display tint
 * (OVERLAY_TINT_ALPHA_SCALE opacity, the stored mask keeps the full values
 * for the erase) and merges the scaled copy into all four channels
 * (RGB = alpha = scaled mask value), the valid premultiplied representation
 * of white at the tint opacity. The test reads the RAW stored bytes
 * (getPixel would unpremultiply and hide a violation) and asserts R,G,B &lt;= A
 * everywhere plus the corner/center extremes in both the raw and the
 * unpremultiplied view.
 * <p>
 * inPlaceBlurAndResizePreserveSmallMask guards the engine's in-place feather
 * (GaussianBlur at MagicEraserEngine.samMaskFeatherKernel(maskSide)) and
 * upsample (INTER_LINEAR resize across sizes)
 * steps on a small synthetic mask: neither op may explode the mask into a
 * large fraction of the image and the mean must be approximately preserved.
 */
@RunWith(AndroidJUnit4.class)
public class MagicEraserOverlayTest {

    private static final int IMAGE_WIDTH = 720;
    private static final int IMAGE_HEIGHT = 1280;

    private static MagicEraserEngine mMagicEraserEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext,
                new MLEngineTestProviderModule());
        // constructing the engine instance loads the OpenCV native library
        // before the first Mat operation; the models stay lazy and are NOT
        // needed here (the connectedDebugAndroidTest runner fresh-installs
        // the test APK with an empty filesDir, so no model is even available)
        mMLEngineProvider.get(MLEngineInstance.class);
        mMagicEraserEngine = mMLEngineProvider.get(MagicEraserEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        if (mMLEngineProvider != null) {
            mMLEngineProvider.dispose();
        }
    }

    /**
     * The overlay of a synthetic mask must be VALID premultiplied data at
     * the display tint: for every pixel the stored R,G,B must not exceed the
     * stored A (a corner stores nothing, the rect center stores white at the
     * OVERLAY_TINT_ALPHA_SCALE opacity), and the unpremultiplied getPixel
     * view must agree (corner 0x00000000, center white at ~half opacity,
     * i.e. RGB all set under an alpha of 255 * OVERLAY_TINT_ALPHA_SCALE).
     */
    @Test
    public void overlayConversion_isPremultipliedValid() {
        Mat mat = syntheticRectMask(IMAGE_WIDTH, IMAGE_HEIGHT, 200, 300);
        MagicEraserMask mask = null;
        Bitmap overlay = null;
        try {
            mask = new MagicEraserMask(mat);
            overlay = mMagicEraserEngine.toOverlayBitmap(mask);
            // raw stored bytes: an ARGB_8888 buffer holds RGBA byte quads in
            // premultiplied form (getPixel would unpremultiply and hide a
            // violated invariant, the all-white overlay proved they diverge)
            ByteBuffer buffer = ByteBuffer.allocate(overlay.getByteCount());
            overlay.copyPixelsToBuffer(buffer);
            int width = overlay.getWidth();
            int height = overlay.getHeight();
            assertEquals("buffer must hold width*height*4 bytes",
                    (long) width * height * 4, buffer.capacity());
            long sampled = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int base = (y * width + x) * 4;
                    int r = buffer.get(base) & 0xFF;
                    int g = buffer.get(base + 1) & 0xFF;
                    int b = buffer.get(base + 2) & 0xFF;
                    int a = buffer.get(base + 3) & 0xFF;
                    assertTrue("premultiplied invariant violated at ("
                                    + x + "," + y + "): rgba=(" + r + "," + g
                                    + "," + b + "," + a + ")",
                            r <= a && g <= a && b <= a);
                    sampled++;
                }
            }
            // corner (mask 0): nothing stored, fully transparent in both views
            assertEquals("corner stored alpha must be 0", 0,
                    buffer.get((5 * width + 5) * 4 + 3) & 0xFF);
            assertEquals("corner must be fully transparent", 0x00000000,
                    overlay.getPixel(5, 5));
            // center of the filled rect (mask 255): white tinted at the
            // display opacity in both views, the stored alpha sits at
            // 255 * 0.5 (rounding allowed)
            int centerBase = ((height / 2) * width + (width / 2)) * 4;
            int centerAlpha = buffer.get(centerBase + 3) & 0xFF;
            assertTrue("rect center stored alpha must be the 0.5 tint of 255, got "
                            + centerAlpha,
                    centerAlpha >= 100 && centerAlpha <= 160);
            assertEquals("rect center stored red must match its alpha",
                    centerAlpha, buffer.get(centerBase) & 0xFF);
            assertEquals("rect center must be white at ~half opacity",
                    0x00FFFFFF, overlay.getPixel(
                            IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2) & 0x00FFFFFF);
            assertNotEquals("rect center must not be fully transparent",
                    0x00000000,
                    overlay.getPixel(IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2));
            System.out.println("OVERLAY_TEST: overlayConversion sampled="
                    + sampled + " pixels, premultiplied invariant holds"
                    + ", corner=0x"
                    + Integer.toHexString(overlay.getPixel(5, 5))
                    + ", center=0x" + Integer.toHexString(
                    overlay.getPixel(IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2)));
        } finally {
            if (overlay != null) {
                overlay.recycle();
            }
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * The engine's in-place feather + upsample sequence on a synthetic raw
     * mask at the size the export actually emits (the fixed 1024x1024
     * output plane — a smaller input leaves it unchanged, measured on
     * device, see
     * EfficientSamProcessor; ~1.5% positives, matching a real SAM raw
     * mask): the engine's feather kernel (3% of the actual plane side via
     * samMaskFeatherKernel) and the in-place resize
     * to the display dimensions must keep the mask a small fraction of the
     * image with an approximately preserved mean.
     */
    @Test
    public void inPlaceBlurAndResizePreserveSmallMask() {
        // the export's fixed output plane side (a smaller input leaves it
        // unchanged, measured on device)
        int maskDims = 1024;
        int rectSize = maskDims / 8;
        int featherKernel = MagicEraserEngine.samMaskFeatherKernel(maskDims);
        Mat pipeline = syntheticRectMask(maskDims, maskDims, rectSize, rectSize);
        try {
            long totalMask = (long) pipeline.cols() * pipeline.rows();
            long before = Core.countNonZero(pipeline);
            double meanBefore = Core.mean(pipeline).val[0];
            Imgproc.GaussianBlur(pipeline, pipeline, new Size(featherKernel, featherKernel), 0);
            long afterBlur = Core.countNonZero(pipeline);
            double meanAfterBlur = Core.mean(pipeline).val[0];
            Imgproc.resize(pipeline, pipeline,
                    new Size(IMAGE_WIDTH, IMAGE_HEIGHT),
                    0, 0, Imgproc.INTER_LINEAR);
            long totalBitmap = (long) pipeline.cols() * pipeline.rows();
            long afterResize = Core.countNonZero(pipeline);
            double meanAfterResize = Core.mean(pipeline).val[0];
            System.out.println("OVERLAY_TEST: inPlace before=" + before
                    + "/" + totalMask + " (" + fraction(before, totalMask)
                    + "), afterBlur=" + afterBlur + " ("
                    + fraction(afterBlur, totalMask) + "), afterResize="
                    + afterResize + "/" + totalBitmap + " ("
                    + fraction(afterResize, totalBitmap) + "), mean "
                    + meanBefore + " -> " + meanAfterBlur + " -> "
                    + meanAfterResize);
            assertTrue("blur must not lose positives (before=" + before
                            + ", afterBlur=" + afterBlur + ")",
                    afterBlur >= before);
            assertTrue("blurred mask must stay a small fraction of the image",
                    afterBlur < totalMask / 2);
            assertTrue("resize must keep positives", afterResize > 0);
            assertTrue("resized mask must stay a small fraction of the image ("
                            + afterResize + " of " + totalBitmap + ")",
                    afterResize < totalBitmap / 2);
            // the feather kernel is normalized and the rect sits far from
            // the borders, the mean is preserved almost exactly
            assertEquals("blur must preserve the mean", meanBefore,
                    meanAfterBlur, meanBefore * 0.05);
            // the interpolating resize is only approximately energy keeping
            assertEquals("resize must preserve the mean", meanAfterBlur,
                    meanAfterResize, meanAfterBlur * 0.25);
        } finally {
            pipeline.release();
        }
    }

    /**
     * The no-object contract at the engine boundary: an all-zero mask
     * (at the size the export actually emits — the fixed 1024x1024 output
     * plane, see EfficientSamProcessor — matching a real SAM raw mask where
     * nothing was segmented) must be reported as "no object" by hasMask, and
     * its overlay conversion must stay fully transparent (every sampled
     * pixel alpha 0), so a no-object detect can never flash an overlay over
     * the image.
     */
    @Test
    public void noObjectContract() {
        int maskDims = 1024;
        Mat mat = new Mat(maskDims, maskDims, CvType.CV_8U, Scalar.all(0));
        MagicEraserMask mask = null;
        Bitmap overlay = null;
        try {
            mask = new MagicEraserMask(mat);
            assertFalse("an all-zero mask must report no object",
                    mMagicEraserEngine.hasMask(mask));
            overlay = mMagicEraserEngine.toOverlayBitmap(mask);
            int width = overlay.getWidth();
            int height = overlay.getHeight();
            assertEquals("overlay width must match the mask", maskDims, width);
            assertEquals("overlay height must match the mask", maskDims, height);
            long sampled = 0;
            for (int y = 0; y < height; y += 16) {
                for (int x = 0; x < width; x += 16) {
                    assertEquals("overlay pixel must be fully transparent at ("
                                    + x + "," + y + ")", 0,
                            Color.alpha(overlay.getPixel(x, y)));
                    sampled++;
                }
            }
            System.out.println("OVERLAY_TEST: noObjectContract sampled="
                    + sampled + " pixels, every sampled alpha is 0"
                    + ", hasMask=false");
        } finally {
            if (overlay != null) {
                overlay.recycle();
            }
            if (mask != null) {
                mask.release();
            }
        }
    }

    /**
     * A width x height CV_8U mask, all zeros, with a centered rectangle of
     * the given size filled 255
     */
    private static Mat syntheticRectMask(int width, int height,
                                         int rectWidth, int rectHeight) {
        Mat mask = new Mat(height, width, CvType.CV_8U, new Scalar(0));
        int left = (width - rectWidth) / 2;
        int top = (height - rectHeight) / 2;
        Imgproc.rectangle(mask,
                new Point(left, top),
                new Point(left + rectWidth, top + rectHeight),
                new Scalar(255), -1);
        return mask;
    }

    private static String fraction(long part, long total) {
        if (total == 0) {
            return "n/a";
        }
        return String.format(Locale.US, "%.4f", (double) part / total);
    }
}
