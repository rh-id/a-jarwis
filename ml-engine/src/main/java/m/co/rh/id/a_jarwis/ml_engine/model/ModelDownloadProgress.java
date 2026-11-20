package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Download progress event of a single AI model, emitted by ModelDownloader
 * through the ModelChangeNotifier.
 */
public final class ModelDownloadProgress {
    /**
     * The opaque model id (as obtained from the engine facades) this
     * progress belongs to
     */
    public final String modelId;
    /**
     * Downloaded bytes so far (including a resumed part)
     */
    public final long bytesDone;
    /**
     * Total bytes to download, 0 when unknown (indeterminate)
     */
    public final long bytesTotal;

    public ModelDownloadProgress(String modelId, long bytesDone, long bytesTotal) {
        this.modelId = modelId;
        this.bytesDone = bytesDone;
        this.bytesTotal = bytesTotal;
    }
}
