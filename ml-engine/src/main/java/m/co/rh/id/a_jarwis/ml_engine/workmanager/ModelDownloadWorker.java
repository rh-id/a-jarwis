package m.co.rh.id.a_jarwis.ml_engine.workmanager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelChecksumException;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelDownloader;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Worker to download a single AI model (see {@link ModelType}) into the app filesDir.
 * Unique work name is per model, so each model is downloaded only once and re-triggering
 * while a download is still running keeps the existing work (ExistingWorkPolicy.KEEP).
 * ALL download events for actual downloads (progress/succeeded/failed) are broadcast
 * by {@link ModelDownloader} through the {@link ModelChangeNotifier}; this worker
 * only emits a failed event with a null model type when the work input is invalid
 * (invalid input never reaches the downloader), and maps exceptions to WorkManager
 * Results (success/failure/retry).
 */
public class ModelDownloadWorker extends Worker {
    private static final String TAG = "ModelDownloadWorker";
    private static final String UNIQUE_WORK_NAME_PREFIX = "model-download-";
    private static final long BACKOFF_DELAY_SECONDS = 30;

    public ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Provider provider = BaseApplication.of(getApplicationContext()).getProvider();
        ILogger logger = provider.get(ILogger.class);
        ModelChangeNotifier modelChangeNotifier = provider.get(ModelChangeNotifier.class);
        ModelType modelType = ModelType.fromId(getInputData().getString(Params.MODEL_TYPE));
        if (modelType == null) {
            String modelTypeId = getInputData().getString(Params.MODEL_TYPE);
            logger.e(TAG, "Unknown model type: " + modelTypeId);
            modelChangeNotifier.downloadFailed(null, "Unknown model type: " + modelTypeId);
            return Result.failure(new Data.Builder()
                    .putString(Params.ERROR_MESSAGE, "Unknown model type: " + modelTypeId)
                    .build());
        }
        ModelDownloader modelDownloader = provider.get(ModelDownloader.class);
        try {
            modelDownloader.download(getApplicationContext(), modelType);
            logger.i(TAG, "Model downloaded: " + modelType.getDisplayName());
            return Result.success();
        } catch (ModelChecksumException e) {
            // permanent failure, downloading again from the same url will not help,
            // the failed event is emitted by ModelDownloader
            logger.e(TAG, "Model " + modelType.getDisplayName() + " failed checksum validation", e);
            return Result.failure(new Data.Builder()
                    .putString(Params.ERROR_MESSAGE, e.getMessage())
                    .build());
        } catch (Exception e) {
            logger.e(TAG, "Failed downloading model " + modelType.getDisplayName()
                    + ": " + e.getMessage(), e);
            // transient failure, the work continues via backoff, no notifier event
            return Result.retry();
        }
    }

    /**
     * @return unique work name for the model download work
     */
    public static String uniqueWorkName(ModelType modelType) {
        return UNIQUE_WORK_NAME_PREFIX + modelType.getId();
    }

    /**
     * Enqueue a one time download work for the model,
     * does nothing when the same work is still pending/running (ExistingWorkPolicy.KEEP)
     */
    public static void enqueue(WorkManager workManager, ModelType modelType) {
        OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(new Data.Builder()
                        .putString(Params.MODEL_TYPE, modelType.getId())
                        .build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build();
        workManager.enqueueUniqueWork(uniqueWorkName(modelType), ExistingWorkPolicy.KEEP,
                oneTimeWorkRequest);
    }
}
