package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.alogger.ILogger;

/**
 * Downloads AI models over HTTP using pure java.net, with Hugging Face client semantics:
 * Hugging Face LFS files expose the file SHA-256 digest in the (X-Linked-)ETag of the
 * resolve URL, which is used to validate the downloaded file; a non-sha256 ETag
 * (e.g. the git-blob SHA-1 exposed for non-LFS files) is treated as unknown checksum
 * and only the content length is validated. Supports resuming an interrupted download
 * through the Range header.
 * ALL download events are broadcast through the injected {@link ModelChangeNotifier}:
 * progress (see {@link ModelChangeNotifier#downloadProgress}), succeeded
 * (see {@link ModelChangeNotifier#downloadSucceeded}) and failed for permanent
 * checksum failures (see {@link ModelChangeNotifier#downloadFailed}); transient IO
 * failures are not emitted because the work is retried (by WorkManager,
 * see ModelDownloadWorker).
 * Must be executed on a background thread.
 */
public class ModelDownloader {
    private static final String TAG = "ModelDownloader";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 8_192;
    private static final int PROGRESS_STEP_BYTES = 65_536;
    private static final String TEMP_FILE_SUFFIX = ".tmp";
    private static final String SHA_256 = "SHA-256";

    private final ILogger mLogger;
    private final FileHelper mFileHelper;
    private final ModelChangeNotifier mModelChangeNotifier;

    public ModelDownloader(ILogger logger, FileHelper fileHelper,
                           ModelChangeNotifier modelChangeNotifier) {
        mLogger = logger;
        mFileHelper = fileHelper;
        mModelChangeNotifier = modelChangeNotifier;
    }

    /**
     * Download the model into its target file (see {@link ModelCatalog#getModelFile}).
     * Emits the download outcome through the injected {@link ModelChangeNotifier}:
     * {@link ModelChangeNotifier#downloadSucceeded} fires on every normal return
     * (including the already-available early return), {@link ModelChangeNotifier#downloadFailed}
     * fires only for the permanent {@link ModelChecksumException} before it is rethrown.
     * Transient {@link IOException}s are NOT emitted, the work is retried instead
     * (by WorkManager, see ModelDownloadWorker).
     *
     * @return immediately without network access when the target file already exists (idempotent)
     * @throws IOException              network/IO failure, retryable
     * @throws ModelChecksumException   checksum/length validation failed permanently,
     *                                  a failed event is emitted before rethrowing
     */
    public void download(@NonNull Context context, @NonNull ModelType modelType)
            throws IOException {
        try {
            downloadInternal(context, modelType);
        } catch (ModelChecksumException e) {
            // permanent failure, downloading again from the same url will not help
            mModelChangeNotifier.downloadFailed(modelType, e.getMessage());
            throw e;
        }
        mModelChangeNotifier.downloadSucceeded(modelType);
    }

    private void downloadInternal(@NonNull Context context, @NonNull ModelType modelType)
            throws IOException {
        File targetFile = ModelCatalog.getModelFile(context, modelType);
        if (targetFile.exists()) {
            mLogger.d(TAG, "Model already available: " + modelType.getDisplayName());
            // the previous attempt may have died between download completion and the atomic move
            if (new File(targetFile.getAbsolutePath() + TEMP_FILE_SUFFIX).delete()) {
                mLogger.d(TAG, "Deleted orphaned temp file of " + modelType.getDisplayName());
            }
            return;
        }
        File tmpFile = new File(targetFile.getAbsolutePath() + TEMP_FILE_SUFFIX);
        // the tmp file is written into the target's directory before the download starts,
        // so its parent dirs must exist (FileOutputStream does not create parents)
        File parentFile = tmpFile.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        DownloadMeta meta = fetchMeta(modelType.getDownloadUrl());

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                // only the first attempt may resume, the retry after checksum mismatch is always clean
                downloadOnce(modelType, modelType.getDownloadUrl(), tmpFile, meta,
                        attempt == 1);
                break;
            } catch (ModelChecksumException e) {
                tmpFile.delete();
                if (attempt >= 2) {
                    throw e;
                }
                mLogger.e(TAG, "Checksum mismatch for " + modelType.getDisplayName()
                        + ", retrying a clean download", e);
            }
        }

        mFileHelper.atomicMove(tmpFile, targetFile);
        mLogger.i(TAG, "Model downloaded: " + modelType.getDisplayName());
    }

    private void downloadOnce(ModelType modelType, String urlStr, File tmpFile, DownloadMeta meta,
                              boolean allowResume) throws IOException {
        long startOffset = 0;
        if (allowResume && tmpFile.exists()) {
            startOffset = tmpFile.length();
        }
        boolean resumed = false;
        // every connection created below is guaranteed to be disconnected, the finally
        // block disconnects the latest non-disconnected connection on any exit path
        HttpURLConnection connection = null;
        int responseCode;
        try {
            while (true) {
                connection = openConnection(urlStr);
                if (startOffset > 0) {
                    connection.setRequestProperty("Range", "bytes=" + startOffset + "-");
                }
                responseCode = connection.getResponseCode();
                if (startOffset > 0) {
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        resumed = true;
                        break;
                    }
                    // server ignored the range (200) or the range is unsatisfiable (416),
                    // restart clean from scratch
                    connection.disconnect();
                    connection = null;
                    tmpFile.delete();
                    startOffset = 0;
                } else {
                    break;
                }
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " for " + urlStr);
            }
            long length = headerAsLong(connection, "Content-Length", -1);
            long bytesTotal;
            if (length > 0) {
                // on a resumed 206 this is only the remaining bytes
                bytesTotal = startOffset + length;
            } else if (meta.contentLength > 0) {
                // fallback from HEAD is already the full file size, do not add the offset
                bytesTotal = meta.contentLength;
            } else {
                bytesTotal = 0;
            }

            MessageDigest messageDigest = getMessageDigest();
            if (resumed) {
                // seed the digest with the already downloaded part
                try (InputStream existing = new FileInputStream(tmpFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = existing.read(buffer)) != -1) {
                        messageDigest.update(buffer, 0, read);
                    }
                }
            }
            long bytesDone = startOffset;
            mModelChangeNotifier.downloadProgress(modelType, bytesDone, bytesTotal);
            long lastReported = bytesDone;
            try (InputStream inputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, messageDigest);
                 OutputStream outputStream = new FileOutputStream(tmpFile, resumed)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = digestInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    bytesDone += read;
                    if (bytesDone - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = bytesDone;
                        mModelChangeNotifier.downloadProgress(modelType, bytesDone, bytesTotal);
                    }
                }
                mModelChangeNotifier.downloadProgress(modelType, bytesDone, bytesTotal);
            }

            String checksum = toHex(messageDigest.digest());
            boolean checksumValid = meta.sha256 == null || meta.sha256.isEmpty()
                    || meta.sha256.equalsIgnoreCase(checksum);
            boolean lengthValid = bytesTotal <= 0 || bytesDone == bytesTotal;
            if (!checksumValid || !lengthValid) {
                throw new ModelChecksumException("Downloaded model " + modelType.getDisplayName()
                        + " failed validation, expected length: " + bytesTotal
                        + ", actual length: " + bytesDone
                        + ", expected checksum: " + meta.sha256
                        + ", actual checksum: " + checksum);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Fetch the expected size and the file sha256 checksum of the model.
     * Redirects are NOT followed here: Hugging Face redirects its resolve url to a Xet CDN
     * whose final ETag is the Xet hash, NOT the file sha256. The real file sha256 and size
     * are only present in the X-Linked-ETag / X-Linked-Size headers of the initial
     * huggingface.co redirect response, hence they are read from there (with ETag /
     * Content-Length as fallback for non redirecting servers).
     * The GET in {@link #downloadOnce} runs with redirects enabled and its streamed bytes
     * are validated against this checksum.
     */
    private DownloadMeta fetchMeta(String urlStr) throws IOException {
        HttpURLConnection connection = openConnection(urlStr);
        connection.setInstanceFollowRedirects(false);
        try {
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            if (responseCode >= 300 && responseCode < 400) {
                String etag = cleanEtag(connection.getHeaderField("X-Linked-ETag"));
                if (etag == null) {
                    etag = cleanEtag(connection.getHeaderField("ETag"));
                }
                // a redirect response's Content-Length is the redirect body size, never
                // the file size, only X-Linked-Size is trusted here
                long contentLength = headerAsLong(connection, "X-Linked-Size", 0);
                return new DownloadMeta(isSha256Hex(etag) ? etag : null, contentLength);
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " for " + urlStr);
            }
            String etag = cleanEtag(connection.getHeaderField("ETag"));
            return new DownloadMeta(isSha256Hex(etag) ? etag : null,
                    headerAsLong(connection, "Content-Length", 0));
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    /**
     * Strip optional quotes and weakness prefix.
     * For Hugging Face LFS files the ETag is the file sha256 hex digest.
     */
    private String cleanEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String result = etag.trim();
        if (result.startsWith("W/")) {
            result = result.substring(2);
        }
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    /**
     * Parse the named header as a long, returns the default value when the header
     * is absent or not a number (a typed-header-getter replacement usable from
     * API 21, the project minSdk).
     */
    private static long headerAsLong(HttpURLConnection connection, String name, long defaultValue) {
        String value = connection.getHeaderField(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns true when the value is exactly 64 hex characters (case-insensitive,
     * cleanEtag preserves the case), i.e. a SHA-256 digest in hex form.
     */
    private static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            stringBuilder.append(String.format(Locale.US, "%02x", b));
        }
        return stringBuilder.toString();
    }

    private static class DownloadMeta {
        final String sha256;
        final long contentLength;

        DownloadMeta(String sha256, long contentLength) {
            this.sha256 = sha256;
            this.contentLength = contentLength;
        }
    }
}
