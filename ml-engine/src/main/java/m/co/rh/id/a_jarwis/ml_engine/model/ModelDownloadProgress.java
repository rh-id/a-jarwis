package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Download progress event of a single AI model, emitted by ModelDownloader
 * through the ModelChangeNotifier.
 */
public final class ModelDownloadProgress {
    /**
     * The model this progress belongs to
     */
    public final ModelType modelType;
    /**
     * Downloaded bytes so far (including a resumed part)
     */
    public final long bytesDone;
    /**
     * Total bytes to download, 0 when unknown (indeterminate)
     */
    public final long bytesTotal;

    public ModelDownloadProgress(ModelType modelType, long bytesDone, long bytesTotal) {
        this.modelType = modelType;
        this.bytesDone = bytesDone;
        this.bytesTotal = bytesTotal;
    }
}
