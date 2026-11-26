package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.os.SystemClock;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelStorageEntry;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.ModelDownloadWorker;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * PUBLIC user-facing storage management of the AI models — this class (not
 * the package-private {@link ModelType}/{@link MLEngineInstance#getModelFile})
 * is the surface external modules consume: it lists every catalog model as a
 * {@link ModelStorageEntry} snapshot (downloaded state, real file size,
 * last-used time, WorkManager download state), deletes a single model
 * (cancelling its running download first) and sweeps the leftover partial
 * {@code "<target>.tmp"} download files. Consumed by the :settings storage
 * page (StoragePage) and the standalone OS manage-space entry point.
 * <p>
 * DELETE-FLOW NATIVE CACHE LAW — do NOT "fix" this later: {@link #deleteModel}
 * only deletes files and the usage record, it NEVER releases/evicts any
 * native processor cache (no STProcessor/PersonSeg/EfficientSam/Lama/
 * AiImageDetect/NafNet/Swin2sr/Depth/SubjectSeg/face releases).
 * app-lifetime processors are TEARDOWN POISON — releasing them outside the
 * dispose cascade SEGVs the next net's first forward (see
 * NafNetProcessor's teardown note and the MLEngineInstance cache javadocs).
 * A cached processor keeping a deleted model's net alive in
 * memory until session end / process death is ACCEPTED by design: the
 * availability gates re-prompt the download on the next session
 * <p>
 * All methods perform disk and WorkManager queries with bounded sleeps:
 * callers must invoke them off the main thread (the storage page runs them
 * on Schedulers.io through its RxDisposer).
 */
public class ModelStorageManager {
    private static final String TAG = "ModelStorageManager";

    /**
     * Total bounded wait for a just-cancelled download worker to finish
     * unwinding (the cancel is async: the worker observes its stop through
     * the ModelDownloader's CancelledCheck between the stream chunks, so it
     * can still be mid-transfer right after cancelUniqueWork returns)
     */
    private static final long WORK_CANCEL_WAIT_MS = 3_000;
    /**
     * Poll interval of the bounded wait above
     */
    private static final long WORK_CANCEL_POLL_MS = 100;
    /**
     * Timeout of every single WorkManager Future query, so listModels (and
     * the settle loop) can never hang on a wedged WorkManager
     */
    private static final long WORK_QUERY_TIMEOUT_SECONDS = 2;
    /**
     * Grace delay of the belt-and-braces resurrection re-check: after the
     * file deletion, a racing download may still commit its atomicMove and
     * recreate the target file (the cancel was observed a chunk too late) —
     * each attempt waits this long, then deletes the resurrected file again
     */
    private static final long RESURRECTION_GRACE_MS = 300;
    /**
     * Bounded retry count of the resurrection re-check, the delete must
     * never turn into a wait loop
     */
    private static final int RESURRECTION_MAX_ATTEMPTS = 3;

    /**
     * User-facing feature label per model id (hardcoded English, the same
     * convention as the ModelType display names): the grouping key of the
     * storage page rows, answering "which feature will re-download this"
     */
    private static final Map<String, String> FEATURE_LABELS = buildFeatureLabels();

    private static Map<String, String> buildFeatureLabels() {
        Map<String, String> featureLabels = new HashMap<>();
        featureLabels.put("subject-segmentation", "Remove Background");
        featureLabels.put("depth-estimation", "Lens Blur");
        featureLabels.put("efficient-sam", "Magic Eraser");
        featureLabels.put("lama", "Magic Eraser");
        featureLabels.put("ai-image-detect", "AI Image Detector");
        featureLabels.put("nafnet-deblur", "Photo Restore (Deblur)");
        featureLabels.put("swin2sr-upscale", "Photo Restore (Upscale)");
        featureLabels.put("person-segmentation", "Portrait Mode");
        featureLabels.put("face-detect", "Blur Face");
        featureLabels.put("face-recognizer", "Blur Face");
        featureLabels.put("nst-mosaic", "Neural Style Transfer");
        featureLabels.put("nst-candy", "Neural Style Transfer");
        featureLabels.put("nst-rain-princess", "Neural Style Transfer");
        featureLabels.put("nst-udnie", "Neural Style Transfer");
        featureLabels.put("nst-pointilism", "Neural Style Transfer");
        return featureLabels;
    }

    private final Context mAppContext;
    private final ILogger mLogger;
    private final WorkManager mWorkManager;
    private final ModelUsageStore mModelUsageStore;
    private final ProviderValue<MLEngineInstance> mMLEngine;

    public ModelStorageManager(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mWorkManager = provider.get(WorkManager.class);
        mModelUsageStore = provider.get(ModelUsageStore.class);
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    /**
     * Snapshot of every catalog model's storage state, in the enum order of
     * the model catalog. The WorkManager download state of each model is
     * queried live with a short Future timeout — one slow query degrades
     * that model's "downloading" flag to false, it never hangs the list.
     * FLAG CONSISTENCY RULE: a downloaded model always reports
     * {@code downloading = false} — the model file on disk is AUTHORITATIVE.
     * A still-RUNNING/ENQUEUED work record for an already-downloaded model
     * is only the worker's tail phase (the downloader emits downloadSucceeded
     * inside doWork BEFORE WorkManager transitions the WorkInfo to
     * SUCCEEDED, so a success refresh can still observe RUNNING) or an
     * idempotent early-return re-run — never an in-flight transfer, because
     * a genuine in-flight download always has no target file yet (the
     * downloader early-returns when it does). This keeps the entry flags
     * mutually consistent: downloaded rows render size + delete,
     * downloading rows (no file) render "Downloading…"/percent with no
     * buttons — without the rule a success-time refresh races the state
     * transition and sticks the row on "Downloading…" forever (no later
     * refresh ever comes).
     * Must be called off the main thread.
     *
     * @return one {@link ModelStorageEntry} per {@link ModelType} value
     */
    public List<ModelStorageEntry> listModels() {
        List<ModelStorageEntry> entries = new ArrayList<>();
        MLEngineInstance engineInstance = mMLEngine.get();
        for (ModelType modelType : ModelType.values()) {
            String modelId = modelType.getId();
            // package-private access is fine here: same module, same package
            File modelFile = engineInstance.getModelFile(modelType);
            // the availability gate also runs the legacy file name migration
            // (see MLEngineInstance.migrateLegacyIfNeeded) when needed
            boolean downloaded = engineInstance.isModelAvailable(modelType);
            long sizeBytes = downloaded && modelFile.exists()
                    ? modelFile.length() : 0L;
            long lastUsedAtMillis = mModelUsageStore.getLastUsed(modelId);
            // see the FLAG CONSISTENCY RULE above: the file wins over a
            // stale RUNNING/ENQUEUED work record
            boolean downloading = isDownloading(modelId) && !downloaded;
            entries.add(new ModelStorageEntry(modelId, modelType.getDisplayName(),
                    featureLabelFor(modelId), downloaded, sizeBytes,
                    lastUsedAtMillis, downloading, modelType.getDisplaySize()));
        }
        return entries;
    }

    /**
     * Delete the model behind the given opaque model id together with its
     * usage record and its partial download leftovers. The running download
     * is cancelled first and given a bounded ~3 s window to unwind (see
     * {@link #waitForWorkToSettle}); a download that committed the file
     * anyway is re-deleted by the bounded resurrection re-check.
     * NATIVE CACHE LAW (see the class javadoc): no processor cache is
     * released — a cached processor of the deleted model keeps its net in
     * memory until its normal release point, accepted by design (the
     * teardown-poison processors must only ever be released by the dispose
     * cascade).
     * Must be called off the main thread (it polls WorkManager and sleeps
     * through the grace delays).
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @return true when the target file is absent at the end
     * @throws IllegalArgumentException unknown model id
     */
    public boolean deleteModel(String modelId) {
        ModelType modelType = ModelType.fromId(modelId);
        if (modelType == null) {
            throw new IllegalArgumentException("Unknown model id: " + modelId);
        }
        mLogger.i(TAG, "Deleting model: " + modelType.getDisplayName());
        // 1. cancel any pending/running download of this model
        mWorkManager.cancelUniqueWork(ModelDownloadWorker.uniqueWorkName(modelId));
        // 2. let the cancelled worker unwind before touching its files: the
        //    cancel is observed between the stream chunks, the worker may
        //    still be mid-transfer right now
        waitForWorkToSettle(modelId);
        // 3. delete the target file and the partial download sibling
        File targetFile = mMLEngine.get().getModelFile(modelType);
        File tmpFile = new File(targetFile.getAbsolutePath()
                + ModelDownloader.TEMP_FILE_SUFFIX);
        deleteQuietly(targetFile, modelType);
        deleteQuietly(tmpFile, modelType);
        // 4. forget the last-used record, the deleted model reports
        //    "never used" again
        mModelUsageStore.clear(modelId);
        // 5. belt-and-braces resurrection re-check: a racing download whose
        //    cancel was observed a chunk too late can still commit its
        //    atomicMove after the deletion above
        for (int attempt = 0; attempt < RESURRECTION_MAX_ATTEMPTS; attempt++) {
            SystemClock.sleep(RESURRECTION_GRACE_MS);
            if (!targetFile.exists()) {
                return true;
            }
            mLogger.i(TAG, "Model file of " + modelType.getDisplayName()
                    + " reappeared after deletion (a racing download committed it)"
                    + ", deleting again (attempt " + (attempt + 1) + "/"
                    + RESURRECTION_MAX_ATTEMPTS + ")");
            targetFile.delete();
        }
        // 6. honest answer: false when the file could not be kept deleted
        boolean absent = !targetFile.exists();
        if (!absent) {
            mLogger.e(TAG, "Model file of " + modelType.getDisplayName()
                    + " is still present after all delete attempts");
        }
        return absent;
    }

    /**
     * Enqueue the on-demand download of the model behind the given opaque
     * model id (ExistingWorkPolicy.KEEP, see ModelDownloadWorker.enqueue: a
     * re-enqueue while pending/running is a no-op). Enqueueing is an async
     * WorkManager write, safe from any thread; listModels sees the work as
     * ENQUEUED immediately
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @throws IllegalArgumentException unknown model id
     */
    public void startDownload(String modelId) {
        if (ModelType.fromId(modelId) == null) {
            throw new IllegalArgumentException("Unknown model id: " + modelId);
        }
        ModelDownloadWorker.enqueue(mWorkManager, modelId);
    }

    /**
     * Sweep the partial {@code "<target>.tmp"} download siblings of every
     * model type (and nothing else) under the model directories — the
     * leftovers of a download killed mid-transfer (process death, cancelled
     * run, app-update migration; a cancelled run deliberately keeps its tmp,
     * see ModelDownloader's cancellation contract). SWEEP GUARD: a model
     * whose unique download work is ENQUEUED/RUNNING is skipped (the same
     * live query listModels uses), so the sweep never cuts the live tmp of
     * an active transfer. The completed model files themselves are never
     * touched. Must be called off the main thread.
     *
     * @return total bytes reclaimed by the successful deletions
     */
    public long deleteIncompleteDownloads() {
        long reclaimedBytes = 0L;
        MLEngineInstance engineInstance = mMLEngine.get();
        for (ModelType modelType : ModelType.values()) {
            if (isDownloading(modelType.getId())) {
                mLogger.d(TAG, "Skipping the partial download of "
                        + modelType.getDisplayName() + ": the download is active");
                continue;
            }
            File targetFile = engineInstance.getModelFile(modelType);
            File tmpFile = new File(targetFile.getAbsolutePath()
                    + ModelDownloader.TEMP_FILE_SUFFIX);
            if (tmpFile.exists()) {
                long length = tmpFile.length();
                if (tmpFile.delete()) {
                    reclaimedBytes += length;
                } else {
                    mLogger.e(TAG, "Failed to delete incomplete download file: "
                            + tmpFile.getAbsolutePath());
                }
            }
        }
        mLogger.i(TAG, String.format(Locale.US,
                "Incomplete download sweep reclaimed %d bytes", reclaimedBytes));
        return reclaimedBytes;
    }

    /**
     * Free-space view of the app internal storage, used by the storage page
     * summary header (same filesystem the model files live in)
     *
     * @return the usable bytes of the app filesDir
     */
    public long getFreeSpaceBytes() {
        return mAppContext.getFilesDir().getUsableSpace();
    }

    /**
     * Total size of the current partial {@code "<target>.tmp"} download
     * leftovers, the read-only counterpart of {@link #deleteIncompleteDownloads()}
     * used by the storage page to show the "Incomplete downloads (X MB)" row
     * before the user decides to sweep. Same sweep guard as the sweep
     * itself: models with an ENQUEUED/RUNNING download are skipped, so the
     * row only reflects true orphans, not the live tmp of an active
     * transfer. Must be called off the main thread.
     *
     * @return total bytes currently occupied by the partial download files
     */
    public long getIncompleteDownloadsBytes() {
        long bytes = 0L;
        MLEngineInstance engineInstance = mMLEngine.get();
        for (ModelType modelType : ModelType.values()) {
            if (isDownloading(modelType.getId())) {
                continue;
            }
            File targetFile = engineInstance.getModelFile(modelType);
            File tmpFile = new File(targetFile.getAbsolutePath()
                    + ModelDownloader.TEMP_FILE_SUFFIX);
            if (tmpFile.exists()) {
                bytes += tmpFile.length();
            }
        }
        return bytes;
    }

    /**
     * Feature label of the given model id (see {@link #FEATURE_LABELS}),
     * falling back to the raw id so the UI always shows something
     */
    private static String featureLabelFor(String modelId) {
        String featureLabel = FEATURE_LABELS.get(modelId);
        return featureLabel == null ? modelId : featureLabel;
    }

    /**
     * True when the unique download work of the given model id is currently
     * ENQUEUED or RUNNING (the same live-query seeding the download dialog
     * uses). The Future is guarded with a short timeout: a wedged WorkManager
     * degrades the answer to "not downloading" instead of hanging the caller.
     */
    private boolean isDownloading(String modelId) {
        try {
            List<WorkInfo> workInfos = mWorkManager.getWorkInfosForUniqueWork(
                            ModelDownloadWorker.uniqueWorkName(modelId))
                    .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return hasActiveWork(workInfos);
        } catch (TimeoutException e) {
            mLogger.e(TAG, "Timed out querying the download work of model "
                    + modelId, e);
        } catch (Exception e) {
            mLogger.e(TAG, "Failed to query the download work of model "
                    + modelId, e);
        }
        return false;
    }

    /**
     * Bounded wait until the unique download work of the given model id has
     * no ENQUEUED/RUNNING state left (or {@link #WORK_CANCEL_WAIT_MS}
     * elapsed — the wait never turns into a hang, the worst case is a delete
     * racing a last chunk, which the resurrection re-check covers)
     */
    private void waitForWorkToSettle(String modelId) {
        long deadline = SystemClock.elapsedRealtime() + WORK_CANCEL_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                List<WorkInfo> workInfos = mWorkManager.getWorkInfosForUniqueWork(
                                ModelDownloadWorker.uniqueWorkName(modelId))
                        .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!hasActiveWork(workInfos)) {
                    return;
                }
            } catch (TimeoutException e) {
                mLogger.e(TAG, "Timed out querying the download work of model "
                        + modelId + " during the cancel settle wait", e);
                return;
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to query the download work of model "
                        + modelId + " during the cancel settle wait", e);
                return;
            }
            SystemClock.sleep(WORK_CANCEL_POLL_MS);
        }
        mLogger.i(TAG, "Download work of model " + modelId
                + " is still active after the bounded cancel wait, proceeding"
                + " with the file deletion");
    }

    /**
     * True when any of the given WorkInfos is ENQUEUED or RUNNING
     */
    private static boolean hasActiveWork(List<WorkInfo> workInfos) {
        if (workInfos == null) {
            return false;
        }
        for (WorkInfo workInfo : workInfos) {
            WorkInfo.State state = workInfo.getState();
            if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort file deletion with a one-line result log per file
     */
    private void deleteQuietly(File file, ModelType modelType) {
        if (file.exists() && !file.delete()) {
            mLogger.e(TAG, "Failed to delete " + file.getAbsolutePath()
                    + " of " + modelType.getDisplayName());
        }
    }
}
