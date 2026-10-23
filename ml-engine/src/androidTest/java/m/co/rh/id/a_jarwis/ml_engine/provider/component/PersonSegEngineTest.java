package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.CvType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link PersonSegEngine}, which will execute on an
 * Android device (models auto-downloaded via {@link ModelTestHelper}),
 * verifying the mask computation and both portrait edit modes.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class PersonSegEngineTest {

    private static PersonSegEngine mPersonSegEngine;
    private static Provider mMLEngineProvider;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider);
        mPersonSegEngine = mMLEngineProvider.get(PersonSegEngine.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the engine cache owns masks from the getMask tests, a global release
        // keeps the suite retry safe when a test stopped after caching a mask
        mPersonSegEngine.releaseAllMasks();
        mMLEngineProvider.dispose();
    }

    /**
     * The mask is the soft person probability back-scaled to the input bitmap
     * resolution, not an argmax selection: a single 8bit channel limited to
     * the 0..255 range with intermediate soft pixel values.
     */
    @Test
    public void computeMask_softRange() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.abel_pacheco_0001);
        PersonSegMask mask = null;
        try {
            mask = mPersonSegEngine.computeMask(bitmap);
            // upsampled to the input bitmap resolution for compositing
            assertEquals(bitmap.getWidth(), mask.mMask.cols());
            assertEquals(bitmap.getHeight(), mask.mMask.rows());
            assertEquals(1, mask.mMask.channels());
            assertEquals(CvType.CV_8U, mask.mMask.depth());
            // scan every pixel (CV_8U single channel bulk row read)
            int cols = mask.mMask.cols();
            int rows = mask.mMask.rows();
            byte[] row = new byte[cols];
            int min = 255;
            int max = 0;
            int softPixelCount = 0;
            for (int y = 0; y < rows; y++) {
                mask.mMask.get(y, 0, row);
                for (int x = 0; x < cols; x++) {
                    int value = row[x] & 0xFF;
                    if (value < min) {
                        min = value;
                    }
                    if (value > max) {
                        max = value;
                    }
                    // between the pure background and pure person values
                    if (value > 0 && value < 255) {
                        softPixelCount++;
                    }
                }
            }
            assertTrue("mask min pixel " + min + " below 0", min >= 0);
            assertTrue("mask max pixel " + max + " above 255", max <= 255);
            assertTrue("mask has no person pixel at all", max > 0);
            assertTrue("mask is a hard binary mask ("
                    + softPixelCount + " soft pixels found)", softPixelCount > 0);
        } finally {
            // computeMask handles are caller owned
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }
    }

    /**
     * A portrait photo of one person must be reported as containing a person.
     */
    @Test
    public void hasPerson_personPhoto() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.abel_pacheco_0001);
        PersonSegMask mask = null;
        try {
            mask = mPersonSegEngine.computeMask(bitmap);
            assertTrue("full portrait photo must contain a person",
                    mPersonSegEngine.hasPerson(mask));
        } finally {
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }
    }

    /**
     * A person free image must be reported as not containing a person. The
     * natural photo (amber) is the negative control: a synthetic pattern
     * image (like the QR test code) can still trigger a segmentation
     * response the area gate cannot fully reject, while natural person free
     * photos segment to an (nearly) empty mask.
     */
    @Test
    public void hasPerson_falseOnPersonFreeImage() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.amber);
        PersonSegMask mask = null;
        try {
            mask = mPersonSegEngine.computeMask(bitmap);
            assertFalse("person free photo must not contain a person",
                    mPersonSegEngine.hasPerson(mask));
        } finally {
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }
    }

    /**
     * getMask hands out the same engine-owned handle for repeated reads of a
     * key (the inference is not re-run) and re-segments after releaseMask.
     */
    @Test
    public void getMask_maskCachedPerKey() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.abel_pacheco_0001);
        Object cacheKey = "PersonSegEngineTest_getMask";
        try {
            PersonSegMask cachedMask = mPersonSegEngine.getMask(cacheKey, bitmap);
            // second read of the same key must reuse the cached handle
            PersonSegMask cachedMask2 = mPersonSegEngine.getMask(cacheKey, bitmap);
            assertSame("repeated getMask of a key must hand out the cached handle",
                    cachedMask, cachedMask2);
            // engine-owned handle must not be released in between
            mPersonSegEngine.releaseMask(cacheKey);
            PersonSegMask reSegmentedMask = mPersonSegEngine.getMask(cacheKey, bitmap);
            assertNotNull(reSegmentedMask);
            assertNotSame("after releaseMask the next read must re-segment",
                    cachedMask, reSegmentedMask);
            assertTrue(mPersonSegEngine.hasPerson(reSegmentedMask));
        } finally {
            // all getMask handles are engine owned, only the global release runs
            mPersonSegEngine.releaseAllMasks();
            bitmap.recycle();
        }
    }

    /**
     * blurBackground keeps the input size, keeps the person region pixel
     * sharp (within the soft mask residual) and blurs the background region.
     */
    @Test
    public void blurBackground_blursBackgroundKeepsPerson() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.abbas_kiarostami_0001);
        PersonSegMask mask = null;
        Bitmap blurred = null;
        try {
            mask = mPersonSegEngine.computeMask(bitmap);
            blurred = mPersonSegEngine.blurBackground(bitmap, mask, new BlurConfig(), 1f);
            // debug artifact on the device for manual inspection
            File tempFile = mMLEngineProvider.get(FileHelper.class)
                    .createTempFile("PersonSegEngineTest_blurBackground.jpg");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                blurred.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            }
            assertEquals(bitmap.getWidth(), blurred.getWidth());
            assertEquals(bitmap.getHeight(), blurred.getHeight());
            // the person center is composited from the sharp original, the
            // soft mask allows a tiny blending residual at the sampled pixel
            int centerExpected = bitmap.getPixel(bitmap.getWidth() / 2,
                    bitmap.getHeight() / 2);
            int centerActual = blurred.getPixel(bitmap.getWidth() / 2,
                    bitmap.getHeight() / 2);
            assertTrue("person center not sharp (expected "
                            + Integer.toHexString(centerExpected) + " got "
                            + Integer.toHexString(centerActual) + ")",
                    maxChannelDiff(centerExpected, centerActual) <= 3);
            // the Gaussian blur must visibly change the sampled edge region,
            // a portrait person does not reach the photo edge frame
            int maxDiff = backgroundEdgeDiff(bitmap, blurred);
            assertTrue("background not blurred (max sampled diff "
                    + maxDiff + ")", maxDiff > 1);
        } finally {
            if (blurred != null) {
                blurred.recycle();
            }
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }
    }

    /**
     * removeBackground keeps only the person: the result is ARGB_8888, the
     * background corners turn fully transparent while the person center
     * stays (nearly) opaque through the mask, for the transparent PNG export.
     */
    @Test
    public void removeBackground_transparentCornersOpaquePerson() throws Exception {
        Bitmap bitmap = decodeTestImage(R.raw.abbas_kiarostami_0001);
        PersonSegMask mask = null;
        Bitmap result = null;
        try {
            mask = mPersonSegEngine.computeMask(bitmap);
            result = mPersonSegEngine.removeBackground(bitmap, mask);
            // debug artifact on the device for manual inspection
            File tempFile = mMLEngineProvider.get(FileHelper.class)
                    .createTempFile("PersonSegEngineTest_removeBackground.png");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                result.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            }
            assertEquals(bitmap.getWidth(), result.getWidth());
            assertEquals(bitmap.getHeight(), result.getHeight());
            // alpha channel must exist for the transparent PNG export
            assertEquals(Bitmap.Config.ARGB_8888, result.getConfig());
            // the top background corners are transparent after the
            // segmentation (the top frame of the head crop photo is the
            // poster background, the rest of the frame belongs to the person
            // or its shadow regions)
            assertTrue("top left corner still opaque",
                    Color.alpha(result.getPixel(0, 0)) < 60);
            assertTrue("top right corner still opaque",
                    Color.alpha(result.getPixel(bitmap.getWidth() - 1, 0)) < 60);
            // the person center keeps (nearly) full opacity
            assertTrue("person center became transparent",
                    Color.alpha(result.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2)) > 200);
        } finally {
            if (result != null) {
                result.recycle();
            }
            if (mask != null) {
                mask.release();
            }
            bitmap.recycle();
        }
    }

    /**
     * Decode one of the instrumented test images from res/raw.
     */
    private Bitmap decodeTestImage(int rawId) {
        Context context = mMLEngineProvider.getContext();
        return BitmapFactory.decodeStream(context.getResources().openRawResource(rawId));
    }

    /**
     * Max channel difference of the sampled photo edge frame (a portrait
     * person does not reach the edge frame), the Gaussian blur visibly
     * changes that region.
     */
    private static int backgroundEdgeDiff(Bitmap original, Bitmap blurred) {
        int width = original.getWidth();
        int height = original.getHeight();
        int maxDiff = 0;
        for (int y = 0; y < height; y += 8) {
            // sample both the exact border and a few pixels inside it, the
            // border-mean variant of the Gaussian replication stays inactive
            // otherwise at only one edge pixel
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, 0, y));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, 11, y));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, width - 1, y));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, width - 12, y));
        }
        for (int x = 0; x < width; x += 8) {
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, x, 0));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, x, 11));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, x, height - 1));
            maxDiff = Math.max(maxDiff, pixelDiff(original, blurred, x, height - 12));
        }
        return maxDiff;
    }

    /**
     * Max per channel color difference of one pixel pair.
     */
    private static int pixelDiff(Bitmap original, Bitmap blurred, int x, int y) {
        return maxChannelDiff(original.getPixel(x, y), blurred.getPixel(x, y));
    }

    /**
     * Max per channel color difference of two pixels.
     */
    private static int maxChannelDiff(int expected, int actual) {
        return Math.max(
                Math.abs(Color.red(expected) - Color.red(actual)),
                Math.max(
                        Math.abs(Color.green(expected) - Color.green(actual)),
                        Math.abs(Color.blue(expected) - Color.blue(actual))));
    }
}
