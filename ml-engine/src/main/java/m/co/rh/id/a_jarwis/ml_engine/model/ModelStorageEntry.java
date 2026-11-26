package m.co.rh.id.a_jarwis.ml_engine.model;

import java.io.Serializable;

/**
 * Immutable snapshot of the storage state of a single AI model, built by
 * ModelStorageManager.listModels() for the storage management UI (the
 * :settings storage page). Each field answers one row question: what the
 * model is (ids and labels), whether its file is on the disk, how much
 * space it occupies, when it was last used and whether a download of it is
 * currently pending/running (queried from WorkManager at snapshot time —
 * like every snapshot field it can be stale the moment it is returned).
 */
public final class ModelStorageEntry implements Serializable {
    /**
     * Fixed for the serialization stability of this POJO across releases
     */
    private static final long serialVersionUID = 1L;

    /**
     * The opaque model id (as obtained from the engine facades)
     */
    public final String modelId;
    /**
     * Human readable model name (the ModelType enum's hardcoded display name)
     */
    public final String displayName;
    /**
     * User-facing feature label the model powers (e.g. "Magic Eraser"),
     * hardcoded English per model id by ModelStorageManager
     */
    public final String featureLabel;
    /**
     * True when the model file is downloaded and usable on the disk
     */
    public final boolean downloaded;
    /**
     * Actual size of the model file on the disk in bytes, 0 when not downloaded
     */
    public final long sizeBytes;
    /**
     * Wall-clock timestamp of the last use (an actual native parse,
     * see ModelUsageStore), 0 = never used
     */
    public final long lastUsedAtMillis;
    /**
     * True when a download of this model is currently ENQUEUED or RUNNING
     * in WorkManager AND the model file is not on the disk — listModels
     * forces this to false for downloaded rows (the file is authoritative:
     * a RUNNING record next to an existing file is only the worker's tail
     * phase, never an in-flight transfer)
     */
    public final boolean downloading;
    /**
     * The catalog's approximate display size (e.g. "~171 MB", the "~"
     * prefix is part of the catalog value), the "how big would the download
     * be" hint the storage rows show for a not-downloaded model; the real
     * on-disk size of a downloaded model is {@link #sizeBytes}
     */
    public final String approxDisplaySize;

    public ModelStorageEntry(String modelId, String displayName, String featureLabel,
                             boolean downloaded, long sizeBytes, long lastUsedAtMillis,
                             boolean downloading, String approxDisplaySize) {
        this.modelId = modelId;
        this.displayName = displayName;
        this.featureLabel = featureLabel;
        this.downloaded = downloaded;
        this.sizeBytes = sizeBytes;
        this.lastUsedAtMillis = lastUsedAtMillis;
        this.downloading = downloading;
        this.approxDisplaySize = approxDisplaySize;
    }
}
