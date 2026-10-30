package m.co.rh.id.a_jarwis.base.provider.component.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

import m.co.rh.id.alogger.AndroidLogger;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

/**
 * Instrumented tests of the ImageHelper decode entry points on non-image and
 * synthetic image files.
 * <p>
 * decodeGarbageBytesThrows guards the decode failure contract: a file holding
 * random bytes is not decodable, so decodeDownscaledBitmap and
 * decodeFullBitmap must throw an IOException instead of ever returning a null
 * bitmap (the callers treat a returned bitmap as non-null, a null would only
 * surface as a downstream NullPointerException).
 * <p>
 * roundTripPreservesOrientation is the sanity counterpart: a small synthetic
 * landscape bitmap saved through createImageTempFile decodes back with its
 * expected (non-rotated) bounds.
 */
@RunWith(AndroidJUnit4.class)
public class ImageHelperDecodeTest {

    private static final String GARBAGE_FILE_PREFIX = "decode_garbage_";
    private static final String ROUNDTRIP_FILE_NAME = "decode_roundtrip.jpg";

    private static Provider mProvider;
    private static ImageHelper mImageHelper;

    @BeforeClass
    public static void beforeAnyTest() {
        Context appContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        mProvider = Provider.createProvider(appContext,
                new ImageHelperTestProviderModule());
        mImageHelper = mProvider.get(ImageHelper.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        if (mProvider != null) {
            mProvider.dispose();
        }
    }

    /**
     * A temp file filled with random bytes must make both decode methods
     * throw an IOException (never a null return), the file is deleted after
     * the assertions
     */
    @Test
    public void decodeGarbageBytesThrows() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File garbageFile = File.createTempFile(GARBAGE_FILE_PREFIX,
                ".jpg", appContext.getCacheDir());
        try {
            byte[] garbage = new byte[2048];
            new Random(42).nextBytes(garbage);
            try (FileOutputStream outputStream = new FileOutputStream(garbageFile)) {
                outputStream.write(garbage);
            }
            Uri garbageUri = Uri.fromFile(garbageFile);
            assertThrows(IOException.class,
                    () -> mImageHelper.decodeDownscaledBitmap(garbageUri, 1280));
            assertThrows(IOException.class,
                    () -> mImageHelper.decodeFullBitmap(garbageUri));
        } finally {
            garbageFile.delete();
        }
    }

    /**
     * A 64x32 landscape bitmap saved via createImageTempFile decodes back
     * non-null with the same orientation bounds (64 wide, 32 high)
     */
    @Test
    public void roundTripPreservesOrientation() throws IOException {
        Bitmap source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        File tempFile = null;
        Bitmap decoded = null;
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(8, 8, 56, 24, paint);
            tempFile = mImageHelper.createImageTempFile(
                    ROUNDTRIP_FILE_NAME, source);
            decoded = mImageHelper.decodeDownscaledBitmap(
                    Uri.fromFile(tempFile), 1280);
            assertNotNull("the decoded bitmap must not be null", decoded);
            assertEquals("the decoded width must match the source", 64,
                    decoded.getWidth());
            assertEquals("the decoded height must match the source", 32,
                    decoded.getHeight());
            System.out.println("IMAGE_HELPER_TEST: roundTrip decoded="
                    + decoded.getWidth() + "x" + decoded.getHeight()
                    + " from " + tempFile.getName());
        } finally {
            if (decoded != null) {
                decoded.recycle();
            }
            if (tempFile != null) {
                tempFile.delete();
            }
            source.recycle();
        }
    }

    /**
     * Registers only the components this test needs: the logger first (the
     * helper constructors resolve it, the BaseProviderModule registers it
     * before the helpers for the same reason), then the same helper
     * registrations the BaseProviderModule performs
     */
    private static class ImageHelperTestProviderModule implements ProviderModule {

        @Override
        public void provides(ProviderRegistry providerRegistry, Provider provider) {
            providerRegistry.register(ILogger.class,
                    () -> new AndroidLogger(ILogger.VERBOSE));
            providerRegistry.register(FileHelper.class, () -> new FileHelper(provider));
            providerRegistry.register(ImageHelper.class, () -> new ImageHelper(provider));
        }

        @Override
        public void dispose(Provider provider) {
            // nothing to dispose
        }
    }
}
