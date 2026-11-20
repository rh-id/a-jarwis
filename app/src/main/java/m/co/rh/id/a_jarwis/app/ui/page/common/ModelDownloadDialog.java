package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadError;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadProgress;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.ModelDownloadWorker;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulViewDialog;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Dialog to prompt and download missing AI model(s) with progress,
 * pops with {@link SelectedChoice#POSITIVE} when all models are downloaded,
 * or {@link SelectedChoice#NEGATIVE} when cancelled
 */
public class ModelDownloadDialog extends StatefulViewDialog<Activity> implements RequireComponent<Provider> {

    private static final String TAG = "ModelDownloadDialog";

    @NavInject
    private transient NavRoute mNavRoute;

    private SelectedChoice mSelectedChoice;

    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient WorkManager mWorkManager;
    private transient RxDisposer mRxDisposer;
    private transient ModelChangeNotifier mModelChangeNotifier;
    private transient ProviderValue<MLEngineInstance> mMLEngineInstance;

    private transient AlertDialog mAlertDialog;
    private transient AppCompatTextView mTextProgress;
    private transient ProgressBar mProgressBar;
    private transient List<String> mModels;
    private transient Map<String, ModelState> mModelStateMap;

    public ModelDownloadDialog() {
        mSelectedChoice = new SelectedChoice();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mWorkManager = mSvProvider.get(WorkManager.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mModelChangeNotifier = mSvProvider.get(ModelChangeNotifier.class);
        mMLEngineInstance = mSvProvider.lazyGet(MLEngineInstance.class);
    }

    @Override
    protected Dialog createDialog(Activity activity) {
        View contentView = activity.getLayoutInflater()
                .inflate(R.layout.dialog_model_download, null);
        AppCompatTextView textMessage = contentView.findViewById(R.id.text_message);
        mTextProgress = contentView.findViewById(R.id.text_progress);
        mProgressBar = contentView.findViewById(R.id.progressBar);
        mModels = parseArgs();
        textMessage.setText(activity.getString(R.string.msg_model_download_prompt,
                buildModelListText(mModels)));
        mAlertDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.title_download_model)
                .setView(contentView)
                .setPositiveButton(R.string.button_download, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        return mAlertDialog;
    }

    @Override
    protected void onShowDialog(DialogInterface dialog) {
        // replace the buttons click listener to keep the dialog open while downloading,
        // dismiss is done manually when download is done or cancelled
        AlertDialog alertDialog = (AlertDialog) dialog;
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                view -> startDownload());
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                view -> cancelDownload());
        // subscribe to the download events and seed the initial state so an already
        // running/finished download (e.g. app restart mid download) shows its state
        attachObservers();
    }

    private List<String> parseArgs() {
        List<String> models = new ArrayList<>();
        if (mNavRoute != null) {
            Serializable args = mNavRoute.getRouteArgs();
            if (args instanceof ModelList) {
                models.addAll(((ModelList) args).getModels());
            }
        }
        return models;
    }

    /**
     * Human readable prompt lines of the models to download, resolved from
     * the opaque ids through the engine instance's display helpers (an
     * unknown id degrades to the raw id so the prompt always shows something)
     */
    private String buildModelListText(List<String> models) {
        MLEngineInstance engineInstance = mMLEngineInstance.get();
        StringBuilder stringBuilder = new StringBuilder();
        for (String modelId : models) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append("\n");
            }
            stringBuilder.append(engineInstance.displayNameFor(modelId))
                    .append(" (")
                    .append(engineInstance.displaySizeFor(modelId))
                    .append(")");
        }
        return stringBuilder.toString();
    }

    private void startDownload() {
        if (mModels == null || mModels.isEmpty()) {
            return;
        }
        Activity activity = getNavigator().getActivity();
        mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.button_download);
        mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mProgressBar.setIndeterminate(true);
        mTextProgress.setVisibility(View.VISIBLE);
        mTextProgress.setText(activity.getString(R.string.downloading_model));
        attachObservers();
        for (String modelId : mModels) {
            ModelDownloadWorker.enqueue(mWorkManager, modelId);
        }
    }

    private void cancelDownload() {
        mSelectedChoice.setSelectedChoice(SelectedChoice.NEGATIVE);
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
        }
    }

    private void attachObservers() {
        mModelStateMap = new HashMap<>();
        for (String modelId : mModels) {
            mModelStateMap.put(modelId, new ModelState());
        }
        // subscribe first so events emitted while the initial state is being
        // seeded are not missed, then seed once per attach to cover events
        // missed while the dialog was disposed (e.g. app restart mid download)
        mRxDisposer.add("attachObservers_progress", mModelChangeNotifier.getProgressFlow()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDownloadProgress));
        mRxDisposer.add("attachObservers_succeeded", mModelChangeNotifier.getSucceededFlow()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDownloadSucceeded));
        mRxDisposer.add("attachObservers_failed", mModelChangeNotifier.getFailedFlow()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDownloadFailed));
        seedInitialState();
    }

    private void seedInitialState() {
        if (mModels == null || mModels.isEmpty()) {
            return;
        }
        for (String modelId : mModels) {
            mRxDisposer.add("seedInitialState_" + modelId,
                    Single.fromFuture(mWorkManager.getWorkInfosForUniqueWork(
                                    ModelDownloadWorker.uniqueWorkName(modelId)))
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe((workInfos, throwable) -> {
                                if (throwable != null) {
                                    mLogger.e(TAG, "Failed to query the work info of model "
                                            + modelId, throwable);
                                } else if (workInfos != null && !workInfos.isEmpty()) {
                                    seedModelState(modelId, workInfos);
                                    // disk check stays active on the seeding path:
                                    // WorkInfo may say RUNNING while the file already
                                    // finished while the dialog was detached
                                    evaluateModelState(true);
                                }
                            }));
        }
    }

    /**
     * Seed the tracked state of the model from its current WorkInfo,
     * a state provided by a live event is never downgraded
     */
    private void seedModelState(String modelId, List<WorkInfo> workInfos) {
        ModelState modelState = mModelStateMap == null
                ? null : mModelStateMap.get(modelId);
        if (modelState == null || modelState.state != DownloadState.UNKNOWN) {
            return;
        }
        WorkInfo currentWorkInfo = pickCurrentWorkInfo(workInfos);
        WorkInfo.State state = currentWorkInfo.getState();
        switch (state) {
            case SUCCEEDED:
                modelState.state = DownloadState.SUCCEEDED;
                break;
            case FAILED:
                modelState.state = DownloadState.FAILED;
                modelState.errorMessage = currentWorkInfo.getOutputData()
                        .getString(Params.ERROR_MESSAGE);
                break;
            case RUNNING:
            case ENQUEUED:
                modelState.state = DownloadState.DOWNLOADING;
                break;
            default:
                // CANCELLED/BLOCKED, the download is not running,
                // keep the prompt state, the user may trigger the download again
                break;
        }
    }

    private void onDownloadProgress(ModelDownloadProgress progress) {
        if (mModelStateMap == null) {
            return;
        }
        ModelState modelState = mModelStateMap.get(progress.modelId);
        if (modelState == null) {
            return;
        }
        if (modelState.state == DownloadState.SUCCEEDED
                || modelState.state == DownloadState.FAILED) {
            // a straggler progress event must not downgrade a terminal outcome
            return;
        }
        modelState.state = DownloadState.DOWNLOADING;
        modelState.bytesDone = progress.bytesDone;
        modelState.bytesTotal = progress.bytesTotal;
        // live progress means the download is demonstrably still running,
        // skip the main-thread disk availability check
        evaluateModelState(false);
    }

    private void onDownloadSucceeded(String modelId) {
        if (mModelStateMap == null) {
            return;
        }
        ModelState modelState = mModelStateMap.get(modelId);
        if (modelState == null) {
            return;
        }
        modelState.state = DownloadState.SUCCEEDED;
        evaluateModelState(true);
    }

    private void onDownloadFailed(ModelDownloadError error) {
        if (mModelStateMap == null) {
            return;
        }
        ModelState modelState = mModelStateMap.get(error.modelId);
        if (modelState == null) {
            if (error.modelId != null) {
                return;
            }
            // failure not tied to a known model (e.g. missing work input id),
            // it cannot be attributed to any of the tracked models, keep the
            // current state
            mLogger.e(TAG, "Download failed for an unknown model: " + error.errorMessage);
            return;
        }
        modelState.state = DownloadState.FAILED;
        modelState.errorMessage = error.errorMessage;
        evaluateModelState(true);
    }

    private void evaluateModelState(boolean checkDiskAvailability) {
        if (mModelStateMap == null || mModels == null || mAlertDialog == null) {
            return;
        }
        INavigator navigator = getNavigator();
        if (navigator == null) {
            return;
        }
        Activity activity = navigator.getActivity();
        boolean hasAnyState = false;
        for (ModelState modelState : mModelStateMap.values()) {
            if (modelState.state != DownloadState.UNKNOWN) {
                hasAnyState = true;
                break;
            }
        }
        if (!hasAnyState) {
            // subscribed but no work info received yet (download not started),
            // keep the prompt state
            return;
        }
        boolean allSucceeded = true;
        boolean anyFailed = false;
        long bytesDone = 0;
        long bytesTotal = 0;
        boolean isProgressKnown = true;
        for (String modelId : mModels) {
            ModelState modelState = mModelStateMap.get(modelId);
            DownloadState state = modelState == null
                    ? DownloadState.UNKNOWN : modelState.state;
            if (state == DownloadState.UNKNOWN || state == DownloadState.DOWNLOADING) {
                // the file-exists fallback only matters for missed terminal events
                // (seeded state or terminal events); during live progress the
                // download is demonstrably still running, so the disk check adds
                // nothing but main-thread IO
                if (checkDiskAvailability && mMLEngineInstance.get().isModelAvailable(modelId)) {
                    // the model file already exists on the disk (the download finished
                    // while the dialog was disposed), treat it as succeeded
                    state = DownloadState.SUCCEEDED;
                }
            }
            if (state != DownloadState.SUCCEEDED) {
                allSucceeded = false;
            }
            if (state == DownloadState.FAILED) {
                anyFailed = true;
            }
            if (state == DownloadState.DOWNLOADING) {
                if (modelState.bytesTotal > 0) {
                    bytesDone += modelState.bytesDone;
                    bytesTotal += modelState.bytesTotal;
                } else {
                    // progress data absent, treat as indeterminate
                    isProgressKnown = false;
                }
            } else if (state == DownloadState.UNKNOWN) {
                // no work info received for this model yet, treat as indeterminate
                isProgressKnown = false;
            }
        }
        if (anyFailed) {
            mProgressBar.setIndeterminate(true);
            mTextProgress.setText(activity.getString(R.string.download_failed));
            mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.retry);
            mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        } else if (allSucceeded) {
            if (mAlertDialog.isShowing()) {
                mSelectedChoice.setSelectedChoice(SelectedChoice.POSITIVE);
                // triggers navigator pop with the dialog result
                mAlertDialog.dismiss();
            }
        } else {
            mProgressBar.setVisibility(View.VISIBLE);
            mTextProgress.setVisibility(View.VISIBLE);
            if (isProgressKnown && bytesTotal > 0) {
                if (mProgressBar.isIndeterminate()) {
                    mProgressBar.setIndeterminate(false);
                }
                int percent = (int) (bytesDone * 100 / bytesTotal);
                mProgressBar.setProgress(percent);
                mTextProgress.setText(activity.getString(R.string.download_progress_,
                        percent,
                        formatBytes(bytesDone),
                        formatBytes(bytesTotal)));
            } else {
                mProgressBar.setIndeterminate(true);
                mTextProgress.setText(activity.getString(R.string.downloading_model));
            }
        }
    }

    /**
     * @return the current WorkInfo of the unique work: the non-terminal one when a
     * new attempt is running, otherwise the latest terminal one
     */
    private WorkInfo pickCurrentWorkInfo(List<WorkInfo> workInfos) {
        for (WorkInfo workInfo : workInfos) {
            WorkInfo.State state = workInfo.getState();
            if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) {
                return workInfo;
            }
        }
        return workInfos.get(workInfos.size() - 1);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1_048_576) {
            return String.format(Locale.US, "%.0f KB", bytes / 1_024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0);
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        // the subscriptions are disposed through the RxDisposer lifecycle
        // (see mSvProvider.dispose())
        mAlertDialog = null;
        mTextProgress = null;
        mProgressBar = null;
        mModels = null;
        mModelStateMap = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mWorkManager = null;
        mRxDisposer = null;
        mModelChangeNotifier = null;
        mMLEngineInstance = null;
    }

    @Override
    protected Serializable getDialogResult() {
        return mSelectedChoice;
    }

    /**
     * Download state of a single model tracked by this dialog
     */
    private enum DownloadState {
        UNKNOWN, DOWNLOADING, SUCCEEDED, FAILED
    }

    /**
     * Holds the current download state and progress of a single model
     */
    private static class ModelState {
        private DownloadState state;
        private long bytesDone;
        private long bytesTotal;
        private String errorMessage;

        private ModelState() {
            state = DownloadState.UNKNOWN;
        }
    }

    /**
     * Navigation args for this dialog, contains the opaque model ids (as
     * obtained from the engine facades) to be downloaded
     */
    public static class ModelList implements Serializable {
        private ArrayList<String> models;

        public ModelList(Collection<String> models) {
            this.models = new ArrayList<>(models);
        }

        public ArrayList<String> getModels() {
            return models;
        }
    }
}
