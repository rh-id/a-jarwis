package m.co.rh.id.a_jarwis.base.provider.component.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Class to provide image handling (bitmap decode, crop and image temp file) through this app
 */
public class ImageHelper {
    private static final String TAG = ImageHelper.class.getName();
    /**
     * Cap for the longest side of a full resolution decode result,
     * applied only as an OutOfMemoryError fallback
     */
    private static final int MAX_DECODE_DIMENSION = 4096;
    /**
     * Cap for the inSampleSize escalation on OutOfMemoryError,
     * the decode is given up when the decode still fails at this sample size
     */
    private static final int MAX_IN_SAMPLE_SIZE = 64;

    private final Context mAppContext;
    private final ProviderValue<ILogger> mLogger;
    private final FileHelper mFileHelper;

    public ImageHelper(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.lazyGet(ILogger.class);
        mFileHelper = provider.get(FileHelper.class);
    }

    /**
     * Crop the given bitmap into the given dest rect
     */
    public Bitmap cropBitmap(Bitmap bitmap, Rect dest) {
        return Bitmap.createBitmap(bitmap, dest.left, dest.top, dest.width(), dest.height());
    }

    /**
     * Create a temp file under the temp root and compress the given bitmap into it
     * as JPEG quality 100. The file is deleted on best effort when the compression fails.
     *
     * @param fileName file name for this file
     * @param bitmap   bitmap to be compressed into the file
     * @return temporary file
     * @throws IOException when failed to create file
     */
    public File createImageTempFile(String fileName, Bitmap bitmap) throws IOException {
        File outFile = mFileHelper.createImageTempFile(fileName);
        try (OutputStream outputStream = new BufferedOutputStream(
                new FileOutputStream(outFile))) {
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            if (!compressed) {
                throw new IOException("Failed to compress the bitmap into: "
                        + outFile.getAbsolutePath());
            }
        } catch (Exception | OutOfMemoryError e) {
            outFile.delete();
            throw e;
        }
        return outFile;
    }

    /**
     * Create temporary image file from the given uri content,
     * the image is decoded with the EXIF orientation applied, downsampled
     * into the given width and height then re-encoded as JPEG quality 90
     *
     * @param fileName file name for this file
     * @param content  content of the file to write to this temp file
     * @return temporary file
     * @throws IOException when failed to create file
     */
    public File createImageTempFile(String fileName, Uri content) throws IOException {
        File outFile = mFileHelper.createImageTempFile(fileName);
        try {
            copyImage(content, outFile);
            return outFile;
        } catch (Exception e) {
            outFile.delete();
            throw e;
        }
    }

    public void copyImage(Uri content, File outFile) throws IOException {
        copyImage(content, outFile, 1280, 720);
    }

    public void copyImage(Uri content, File outFile, int width, int height) throws IOException {
        ContentResolver contentResolver = mAppContext.getContentResolver();
        BitmapFactory.Options bmOptions;
        try (ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(content, "r");
             InputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
            bmOptions = getBitmapOptionForCompression(fis, width, height);
        }
        try (OutputStream fileOutputStream = new BufferedOutputStream(
                new FileOutputStream(outFile), 10240)) {
            Bitmap bitmap = decodeBitmapInternal(content, bmOptions);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fileOutputStream);
            fileOutputStream.flush();
        }
    }

    private BitmapFactory.Options getBitmapOptionForCompression(InputStream fis, int width, int height) {
        BitmapFactory.Options bmOptions = new BitmapFactory.Options();
        bmOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(fis, null, bmOptions);
        int inWidth = bmOptions.outWidth;
        int inHeight = bmOptions.outHeight;
        int outWidth = width;
        int outHeight = height;
        if (inHeight > inWidth) {
            outHeight = width;
            outWidth = height;
        }
        int scaleFactor = Math.max(1, Math.min(inWidth / outWidth, inHeight / outHeight));
        bmOptions.inJustDecodeBounds = false;
        bmOptions.inSampleSize = scaleFactor;
        return bmOptions;
    }

    /**
     * Decode the image referenced by the given URI at its full resolution,
     * with the EXIF orientation applied so the result orientation matches
     * what the user sees on the gallery.
     * When decoding at full resolution causes an OutOfMemoryError, the decode is
     * retried with an increasing inSampleSize so the longest side of the result is
     * at most {@link #MAX_DECODE_DIMENSION} pixels.
     *
     * @param content uri of the image to be decoded
     * @return the decoded bitmap
     * @throws IOException when failed to read the image
     */
    public Bitmap decodeFullBitmap(Uri content) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int inSampleSize = 1;
        while (true) {
            try {
                return decodeBitmapInternal(content, options);
            } catch (OutOfMemoryError error) {
                if (inSampleSize >= MAX_IN_SAMPLE_SIZE) {
                    throw error;
                }
                inSampleSize = nextInSampleSizeForMaxDimension(content, inSampleSize);
                options.inSampleSize = inSampleSize;
            }
        }
    }

    /**
     * Decode the image referenced by the given URI with the EXIF orientation applied,
     * downsampled so its longest side is around the given max dimension.
     * The actual result dimension depends on the decoder power-of-2 downsampling,
     * it is always between maxDimension and 2x maxDimension.
     *
     * @param content      uri of the image to be decoded
     * @param maxDimension max dimension of the result, 0 or less means no downsampling
     * @return the decoded bitmap
     * @throws IOException when failed to read the image
     */
    public Bitmap decodeDownscaledBitmap(Uri content, int maxDimension) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inJustDecodeBounds = true;
        decodeBounds(content, options);
        int maxSide = Math.max(options.outWidth, options.outHeight);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        if (maxDimension > 0) {
            while (maxSide / (options.inSampleSize * 2) >= maxDimension) {
                options.inSampleSize *= 2;
            }
        }
        while (true) {
            try {
                return decodeBitmapInternal(content, options);
            } catch (OutOfMemoryError error) {
                if (options.inSampleSize >= MAX_IN_SAMPLE_SIZE) {
                    throw error;
                }
                options.inSampleSize *= 2;
            }
        }
    }

    /**
     * Decode the effective dimension of the image referenced by the given URI
     * (EXIF rotation applied) without loading the bitmap into memory.
     *
     * @param content uri of the image
     * @return int array of the effective {width, height}
     * @throws IOException when failed to read the image
     */
    public int[] decodeImageDimension(Uri content) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        decodeBounds(content, options);
        int width = options.outWidth;
        int height = options.outHeight;
        int rotation = readExifRotation(content);
        if (rotation == 90 || rotation == 270) {
            int temp = width;
            width = height;
            height = temp;
        }
        return new int[]{width, height};
    }

    /**
     * Decode the image referenced by the given URI using the given options,
     * with the EXIF orientation applied so the result orientation matches
     * what the user sees on the gallery.
     */
    private Bitmap decodeBitmapInternal(Uri content, BitmapFactory.Options bmOptions) throws IOException {
        int rotation = readExifRotation(content);
        Bitmap bitmap;
        try (ParcelFileDescriptor pfd = mAppContext.getContentResolver()
                .openFileDescriptor(content, "r")) {
            bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bmOptions);
        }
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.setRotate(rotation);
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                    bitmap.getHeight(), matrix, true);
            if (rotatedBitmap != bitmap) {
                bitmap.recycle();
            }
            bitmap = rotatedBitmap;
        }
        return bitmap;
    }

    private int readExifRotation(Uri content) throws IOException {
        try (ParcelFileDescriptor pfd = mAppContext.getContentResolver()
                .openFileDescriptor(content, "r")) {
            ExifInterface exifInterface = new ExifInterface(pfd.getFileDescriptor());
            return getRotation(exifInterface);
        }
    }

    private BitmapFactory.Options decodeBounds(Uri content, BitmapFactory.Options options) throws IOException {
        try (ParcelFileDescriptor pfd = mAppContext.getContentResolver()
                .openFileDescriptor(content, "r")) {
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
        }
        return options;
    }

    /**
     * @return the next inSampleSize (at least double the given one) so that
     * the decoded longest side is at most {@link #MAX_DECODE_DIMENSION} pixels
     */
    private int nextInSampleSizeForMaxDimension(Uri content, int currentInSampleSize) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        decodeBounds(content, options);
        int maxSide = Math.max(options.outWidth, options.outHeight);
        int inSampleSize = currentInSampleSize * 2;
        while (maxSide / inSampleSize > MAX_DECODE_DIMENSION) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    private int getRotation(ExifInterface exifInterface) {
        int rotation = 0;
        int exifRotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);

        if (exifRotation != ExifInterface.ORIENTATION_UNDEFINED) {
            switch (exifRotation) {
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotation = 270;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotation = 90;
                    break;
            }
        }
        return rotation;
    }
}
