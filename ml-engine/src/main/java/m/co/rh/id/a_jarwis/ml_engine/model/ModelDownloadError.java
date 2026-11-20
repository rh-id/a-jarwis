package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Permanent download failure event of a single AI model, emitted through the
 * ModelChangeNotifier by ModelDownloader (checksum validation failure) and by
 * ModelDownloadWorker when the work input is invalid (missing/unknown model id).
 */
public final class ModelDownloadError {
    /**
     * The opaque model id (as obtained from the engine facades) of the model
     * that failed to download,
     * may be null when the failure is not tied to a known model
     */
    public final String modelId;
    /**
     * Description of the failure
     */
    public final String errorMessage;

    public ModelDownloadError(String modelId, String errorMessage) {
        this.modelId = modelId;
        this.errorMessage = errorMessage;
    }
}
