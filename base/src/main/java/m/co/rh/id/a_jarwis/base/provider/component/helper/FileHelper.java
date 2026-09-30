package m.co.rh.id.a_jarwis.base.provider.component.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Class to provide files through this app
 */
public class FileHelper {
    private static final String TAG = FileHelper.class.getName();

    private final Context mAppContext;
    private final ProviderValue<ILogger> mLogger;
    private final File mLogFile;
    private final File mTempFileRoot;

    public FileHelper(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.lazyGet(ILogger.class);
        File cacheDir = mAppContext.getCacheDir();
        mLogFile = new File(cacheDir, "alogger/app.log");
        mTempFileRoot = new File(cacheDir, "/tmp");
        mTempFileRoot.mkdirs();
    }

    /**
     * Copy the content of the source file into the target file using a buffered stream.
     *
     * @param source file to copy from
     * @param target file to copy to (overwritten if it exists)
     * @throws IOException when failed to copy the file
     */
    public void copyFile(File source, File target) throws IOException {
        try (InputStream inputStream = new FileInputStream(source);
             OutputStream outputStream = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    /**
     * Move the source file to the target file, creating the target parent directories first.
     * Falls back to copy and delete when an atomic rename is not possible
     * (e.g. when the source and the target are on different file systems).
     * The fallback copies into a sibling temporary file first and renames it to the
     * target only after the copy succeeded, so a mid-copy failure never leaves a
     * truncated target behind.
     *
     * @param source file to move
     * @param target destination file (replaced if it exists)
     * @throws IOException when failed to move the file
     */
    public void atomicMove(File source, File target) throws IOException {
        File parentFile = target.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to delete old file: " + target.getAbsolutePath());
        }
        if (!source.renameTo(target)) {
            // the source may be the sibling "<target>.tmp", the copy temp must use a
            // different suffix to avoid colliding with the source path
            File copyTemp = new File(parentFile, target.getName() + ".copy");
            copyTemp.delete();
            try {
                copyFile(source, copyTemp);
            } catch (IOException e) {
                copyTemp.delete();
                throw e;
            }
            if (!copyTemp.renameTo(target)) {
                copyTemp.delete();
                throw new IOException("Failed to rename the copied file to: "
                        + target.getAbsolutePath());
            }
            source.delete();
        }
    }

    public File createTempFile() throws IOException {
        return createTempFile(UUID.randomUUID().toString());
    }

    /**
     * Create temporary file
     *
     * @param fileName file name for this file, a random uuid is used when null or empty
     * @return temporary file
     * @throws IOException when failed to create file
     */
    public File createTempFile(String fileName) throws IOException {
        File parent = new File(mTempFileRoot, UUID.randomUUID().toString());
        parent.mkdirs();
        String fName = fileName;
        if (fName == null || fName.isEmpty()) {
            fName = UUID.randomUUID().toString();
        }
        File tmpFile = new File(parent, fName);
        tmpFile.createNewFile();
        return tmpFile;
    }

    /**
     * Create a temporary file and copy the given content into it as a raw
     * buffered byte copy, so the copied bytes (and any EXIF) are identical to
     * the source.
     *
     * @param fileName file name for this file, a random uuid is used when null or empty
     * @param content  content of the file to write to this temp file
     * @return temporary file
     * @throws IOException when failed to create or copy the file
     */
    public File createTempFile(String fileName, Uri content) throws IOException {
        File parent = new File(mTempFileRoot, UUID.randomUUID().toString());
        parent.mkdirs();
        String fName = fileName;
        if (fName == null || fName.isEmpty()) {
            fName = UUID.randomUUID().toString();
        }
        File tmpFile = new File(parent, fName);
        tmpFile.createNewFile();

        if (content != null) {
            ContentResolver cr = mAppContext.getContentResolver();
            InputStream inputStream = cr.openInputStream(content);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);

            FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            byte[] buff = new byte[8192];
            int b = bufferedInputStream.read(buff);
            while (b != -1) {
                // write only the bytes read so the last chunk never carries
                // stale tail bytes from the previous iteration
                bufferedOutputStream.write(buff, 0, b);
                b = bufferedInputStream.read(buff);
            }
            bufferedOutputStream.close();
            fileOutputStream.close();
            bufferedInputStream.close();
            inputStream.close();
        }
        return tmpFile;
    }

    public void clearLogFile() {
        if (mLogFile.exists()) {
            mLogFile.delete();
            try {
                mLogFile.createNewFile();
            } catch (Throwable throwable) {
                mLogger.get().e(TAG, "Failed to create new file for log", throwable);
            }
        }
    }

    public File getLogFile() {
        return mLogFile;
    }

    /**
     * Delete the temp files with the given file name prefix, best effort,
     * any failure is ignored.
     *
     * @param fileNamePrefix file name prefix of the temp files to be deleted
     */
    public void deleteTempFiles(String fileNamePrefix) {
        deleteTempFiles(fileNamePrefix, 0L);
    }

    /**
     * Delete the temp files with the given file name prefix whose last modified
     * time is older than the given max age, best effort, any failure is ignored.
     * A max age of 0 deletes every matching file, a positive max age keeps the
     * recent files (e.g. the source copies of a restored editor session).
     *
     * @param fileNamePrefix file name prefix of the temp files to be deleted
     * @param maxAgeMillis   maximum age of the files to be deleted in milliseconds
     */
    public void deleteTempFiles(String fileNamePrefix, long maxAgeMillis) {
        long modifiedCutoff = System.currentTimeMillis() - maxAgeMillis;
        File[] parentDirs = mTempFileRoot.listFiles();
        if (parentDirs == null) {
            return;
        }
        for (File parentDir : parentDirs) {
            File[] files = parentDir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.getName().startsWith(fileNamePrefix)
                        && file.lastModified() < modifiedCutoff) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Create an empty temporary image file under the temp root,
     * used as the backing for the image temp file creation on {@link ImageHelper}
     *
     * @param fileName file name for this file
     * @return temporary file
     * @throws IOException when failed to create file
     */
    public File createImageTempFile(String fileName) throws IOException {
        File parent = new File(mTempFileRoot, UUID.randomUUID().toString());
        parent.mkdirs();
        File tmpFile = new File(parent, fileName);
        tmpFile.createNewFile();
        return tmpFile;
    }
}
