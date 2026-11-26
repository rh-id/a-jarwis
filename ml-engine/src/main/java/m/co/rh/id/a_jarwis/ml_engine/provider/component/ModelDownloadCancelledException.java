package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import java.io.IOException;

/**
 * Thrown by the ModelDownloader when a download was cancelled through its
 * {@link CancelledCheck} (e.g. a stopped ModelDownloadWorker run after
 * ModelStorageManager.deleteModel cancelled the unique work). This is NOT a
 * failure of the model or the network: it is deliberately not retryable
 * (the caller withdrew its interest, a retry would restart a download
 * nobody asked for), and NO notifier event fires for it (no downloadFailed:
 * nothing failed; no downloadSucceeded: nothing was published — the
 * cancelled state is observable through WorkManager instead). The partial
 * tmp and its safe handling on this path are covered by the ModelDownloader
 * class javadoc. It is a checked {@link IOException} so the existing
 * download signatures stay unchanged.
 */
public class ModelDownloadCancelledException extends IOException {

    public ModelDownloadCancelledException(String message) {
        super(message);
    }
}
