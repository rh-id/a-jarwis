package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Permanent download failure event of a single AI model, emitted through the
 * ModelChangeNotifier by ModelDownloader (checksum validation failure) and by
 * ModelDownloadWorker when the work input is invalid (null model type).
 */
public final class ModelDownloadError {
    /**
     * The model that failed to download,
     * may be null when the failure is caused by an unknown model type id
     */
    public final ModelType modelType;
    /**
     * Description of the failure
     */
    public final String errorMessage;

    public ModelDownloadError(ModelType modelType, String errorMessage) {
        this.modelType = modelType;
        this.errorMessage = errorMessage;
    }
}
