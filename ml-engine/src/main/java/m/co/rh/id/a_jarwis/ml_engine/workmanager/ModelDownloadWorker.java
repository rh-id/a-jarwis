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
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelChecksumException;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelDownloadCancelledException;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelDownloader;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Worker to download a single AI model into the app filesDir, keyed by the
 * opaque model id obtained from the engine facades (the worker never names
 * the model catalog enum). Unique work name is per model id, so each model is
 * downloaded only once and re-triggering while a download is still running
 * keeps the existing work (ExistingWorkPolicy.KEEP).
 * ALL download events for actual downloads (progress/succeeded/failed) are broadcast
 * by {@link ModelDownloader} through the {@link ModelChangeNotifier}; this worker
 * only emits a failed event with a null model id when the work input is missing
 * (invalid input never reaches the downloader), and maps exceptions to WorkManager
 * Results (success/failure/retry).
 * CANCELLATION CONTRACT: the download runs with {@code this::isStopped} as the
 * downloader's {@link CancelledCheck} — WorkManager's cancelUniqueWork /
 * stop policies do NOT interrupt the (non-interruptible) HttpURLConnection
 * transfer, the polled check is the only effective stop. Every catch block
 * consults {@link #isStopped()} FIRST: a stopped run returns
 * {@link Result#failure()} without retry and without an error log (a
 * cancelled download is a withdrawal of interest, not a failure), and the
 * downloader's {@link ModelDownloadCancelledException} is mapped the same
 * way. It must never come back as {@link Result#retry()}. The partial tmp
 * of a cancelled run is KEPT by the downloader (see its class javadoc).
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
        String modelId = getInputData().getString(Params.MODEL_TYPE);
        if (modelId == null) {
            logger.e(TAG, "Missing model id");
            modelChangeNotifier.downloadFailed(null, "Missing model id");
            return Result.failure(new Data.Builder()
                    .putString(Params.ERROR_MESSAGE, "Missing model id")
                    .build());
        }
        ModelDownloader modelDownloader = provider.get(ModelDownloader.class);
        try {
            modelDownloader.download(getApplicationContext(), modelId, this::isStopped);
            logger.i(TAG, "Model downloaded: " + modelId);
            return Result.success();
        } catch (ModelDownloadCancelledException e) {
            logger.d(TAG, "Download of model " + modelId + " was cancelled");
            return cancelledResult();
        } catch (ModelChecksumException e) {
            if (isStopped()) {
                // a stopped run must not be misreported as a model failure
                logger.d(TAG, "Download of model " + modelId
                        + " cancelled (worker stopped)");
                return cancelledResult();
            }
            // permanent failure, downloading again from the same url will not help,
            // the failed event is emitted by ModelDownloader
            logger.e(TAG, "Model " + modelId + " failed checksum validation", e);
            return Result.failure(new Data.Builder()
                    .putString(Params.ERROR_MESSAGE, e.getMessage())
                    .build());
        } catch (IllegalArgumentException e) {
            if (isStopped()) {
                // a stopped run must not be misreported as a model failure
                logger.d(TAG, "Download of model " + modelId
                        + " cancelled (worker stopped)");
                return cancelledResult();
            }
            logger.e(TAG, "Unknown model id: " + modelId);
            modelChangeNotifier.downloadFailed(modelId, e.getMessage());
            return Result.failure(new Data.Builder()
                    .putString(Params.ERROR_MESSAGE, e.getMessage())
                    .build());
        } catch (Exception e) {
            if (isStopped()) {
                // a stopped run must NEVER retry: the generic transient path
                // below would resurrect a download the user just cancelled
                // (e.g. through ModelStorageManager.deleteModel)
                logger.d(TAG, "Download of model " + modelId
                        + " cancelled (worker stopped)");
                return cancelledResult();
            }
            logger.e(TAG, "Failed downloading model " + modelId
                    + ": " + e.getMessage(), e);
            // transient failure, the work continues via backoff, no notifier event
            return Result.retry();
        }
    }

    /**
     * Failure output of a cancelled (stopped) run: terminal on purpose, with
     * the cancellation noted in the output data so a caller inspecting the
     * WorkInfo can tell it apart from a real failure
     */
    @NonNull
    private static Result cancelledResult() {
        return Result.failure(new Data.Builder()
                .putString(Params.ERROR_MESSAGE, "Download cancelled")
                .build());
    }

    /**
     * @return unique work name for the model download work of the given model id
     */
    public static String uniqueWorkName(String modelId) {
        return UNIQUE_WORK_NAME_PREFIX + modelId;
    }

    /**
     * Enqueue a one time download work for the model behind the given opaque
     * model id, does nothing when the same work is still pending/running
     * (ExistingWorkPolicy.KEEP)
     */
    public static void enqueue(WorkManager workManager, String modelId) {
        OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(new Data.Builder()
                        .putString(Params.MODEL_TYPE, modelId)
                        .build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build();
        workManager.enqueueUniqueWork(uniqueWorkName(modelId), ExistingWorkPolicy.KEEP,
                oneTimeWorkRequest);
    }
}
