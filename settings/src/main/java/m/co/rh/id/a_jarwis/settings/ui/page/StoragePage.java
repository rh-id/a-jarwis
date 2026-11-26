package m.co.rh.id.a_jarwis.settings.ui.page;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.ui.component.AppBarSV;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadError;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadProgress;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelStorageEntry;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelStorageManager;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.a_jarwis.settings.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * Storage management page: shows the AI model storage state (grouped by
 * feature, with dividers between the groups) and groups the whole top
 * section (total downloaded, free space, delete-all, the partial-download
 * leftovers and the app cache rows) into one filled Material 3 card, each
 * section with its delete/clear action. All disk/WorkManager queries run
 * off the main thread (Single.fromCallable on Schedulers.io through the
 * page's RxDisposer), the re-render happens on the main thread by clearing
 * and re-filling the two container LinearLayouts of
 * {@link R.layout#page_storage} (the card's inner summary container and the
 * model rows container below the card), and every mutation path funnels
 * back through a fresh snapshot load.
 * The page exists in two hosts: pushed as a route from the settings menu
 * ({@code standalone == false}, back navigation pops the route) and as the
 * single page of the OS manage-space activity ({@code standalone == true},
 * the app bar shows the BACK arrow and finishing the activity disposes the
 * route) — the flag is a constructor boolean because :settings cannot
 * reference :app classes to type-check the host activity.
 */
public class StoragePage extends StatefulView<Activity> implements RequireComponent<Provider> {
    private static final String TAG = StoragePage.class.getName();

    @NavInject
    private AppBarSV mAppBarSV;
    @NavInject
    private transient INavigator mNavigator;
    private final boolean mStandalone;

    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient RxDisposer mRxDisposer;
    private transient FileHelper mFileHelper;
    private transient ModelStorageManager mModelStorageManager;
    /**
     * Live download event source of the page: the progress flow drives the
     * per-row percent updates through {@link #mRowStatusViews}, the
     * succeeded/failed flows drive the list refresh (same notifier the
     * editor-flow download dialog listens to)
     */
    private transient ModelChangeNotifier mModelChangeNotifier;
    private transient LinearLayout mSummaryRows;
    private transient LinearLayout mModelRows;
    /**
     * Rendered model rows by model id, rebuilt on every render pass: the
     * live progress events look the status line up here and events for rows
     * that are no longer rendered are silently ignored
     */
    private transient Map<String, ModelRowStatus> mRowStatusViews;

    public StoragePage(boolean standalone) {
        mStandalone = standalone;
        mAppBarSV = new AppBarSV();
        if (standalone) {
            // no drawer in the manage-space host: back arrow + finish
            // (see setBackIconOnInitialRoute)
            mAppBarSV.setBackIconOnInitialRoute(true);
        }
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mModelStorageManager = mSvProvider.get(ModelStorageManager.class);
        mModelChangeNotifier = mSvProvider.get(ModelChangeNotifier.class);
        mRowStatusViews = new HashMap<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View view = activity.getLayoutInflater().inflate(R.layout.page_storage,
                container, false);
        mAppBarSV.setTitle(activity.getString(R.string.storage_title));
        if (mStandalone) {
            mAppBarSV.setNavigationOnClick(v -> activity.finish());
        }
        ViewGroup containerAppBar = view.findViewById(R.id.container_app_bar);
        containerAppBar.addView(mAppBarSV.buildView(activity, container));
        mSummaryRows = view.findViewById(R.id.container_summary);
        mModelRows = view.findViewById(R.id.container_models);
        attachModelChangeObservers();
        loadState();
        return view;
    }

    /**
     * Subscribe to the download events under stable RxDisposer keys (add
     * replaces the previous subscription with the same key, so a re-attach
     * never duplicates): progress updates the matching rendered row's status
     * line live, succeeded/failed refresh the whole list from a fresh
     * snapshot. All callbacks arrive on the main thread
     */
    private void attachModelChangeObservers() {
        mRxDisposer.add("attachModelChangeObservers_progress",
                mModelChangeNotifier.getProgressFlow()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::onModelDownloadProgress));
        mRxDisposer.add("attachModelChangeObservers_succeeded",
                mModelChangeNotifier.getSucceededFlow()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(modelId -> loadState()));
        mRxDisposer.add("attachModelChangeObservers_failed",
                mModelChangeNotifier.getFailedFlow()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::onModelDownloadFailed));
    }

    /**
     * Live progress of one model download: rebuild the matching row's status
     * line with the percent (the immutable last-used suffix of the row is
     * preserved). Events for rows that are no longer rendered (stale page,
     * list refreshed meanwhile) are ignored
     */
    @SuppressLint("SetTextI18n")
    private void onModelDownloadProgress(ModelDownloadProgress progress) {
        ModelRowStatus rowStatus = mRowStatusViews == null
                ? null : mRowStatusViews.get(progress.modelId);
        Activity activity = mNavigator == null ? null : mNavigator.getActivity();
        if (rowStatus == null || activity == null) {
            return;
        }
        String state;
        if (progress.bytesTotal > 0) {
            int percent = (int) (progress.bytesDone * 100 / progress.bytesTotal);
            state = activity.getString(R.string.storage_download_progress, percent);
        } else {
            // indeterminate download, the catalog size is unknown to the server
            state = activity.getString(R.string.storage_downloading);
        }
        // both parts are localized separately (format strings above), the
        // concatenated separator + suffix are locale-neutral
        rowStatus.statusView.setText(state + " • " + rowStatus.lastUsedSuffix);
    }

    /**
     * A download finished (the notifier event only carries the id, the
     * outcome state lives in the snapshot): re-render the whole list, the
     * row flips to its downloaded size (or stays for the next flow)
     */
    private void onModelDownloadFailed(ModelDownloadError error) {
        if (error.modelId == null) {
            // failure not tied to a known model (e.g. invalid work input),
            // not actionable from this page
            return;
        }
        showToast(R.string.msg_storage_download_failed);
        loadState();
    }

    /**
     * Load the storage snapshot off the main thread and re-render the page
     * when it arrives. Every mutation path (delete model / delete all /
     * clear incomplete / clear cache) funnels back through this, so the
     * rendered rows always reflect a fresh snapshot
     */
    private void loadState() {
        mRxDisposer.add("loadState",
                Single.fromCallable(this::buildUiState)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((uiState, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to build the storage state",
                                        throwable);
                            } else {
                                renderState(uiState);
                            }
                        }));
    }

    /**
     * Gather everything the page renders in one background pass: the model
     * list (which itself queries WorkManager), the free space, the partial
     * download size and the app cache size
     */
    private UiState buildUiState() {
        UiState uiState = new UiState();
        uiState.entries = mModelStorageManager.listModels();
        for (ModelStorageEntry entry : uiState.entries) {
            if (entry.downloaded) {
                uiState.totalDownloadedBytes += entry.sizeBytes;
            }
        }
        uiState.freeSpaceBytes = mModelStorageManager.getFreeSpaceBytes();
        uiState.incompleteBytes = mModelStorageManager.getIncompleteDownloadsBytes();
        uiState.cacheBytes = computeCacheSize(mSvProvider.getContext().getCacheDir());
        return uiState;
    }

    /**
     * Recursive size of every file under the given directory, the "App
     * cache" figure (the logger file + the temp file tree live under cacheDir)
     */
    private static long computeCacheSize(File directory) {
        long bytes = 0L;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0L;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                bytes += computeCacheSize(file);
            } else {
                bytes += file.length();
            }
        }
        return bytes;
    }

    /**
     * Rebuild both sections from the snapshot: the summary CARD content
     * (total downloaded + Delete all row, free space line, then the divider
     * and the incomplete/app-cache rows — the whole top section grouped into
     * one filled Material 3 card, see page_storage.xml) and the rows section
     * below the card (the models grouped by feature label with the group
     * dividers). Runs on the main thread
     */
    private void renderState(UiState uiState) {
        Activity activity = mNavigator == null ? null : mNavigator.getActivity();
        if (mSummaryRows == null || mModelRows == null || activity == null) {
            // the page was disposed while the snapshot was in flight
            return;
        }
        mSummaryRows.removeAllViews();
        mSummaryRows.addView(buildSummarySection(activity, uiState));
        if (uiState.incompleteBytes > 0) {
            mSummaryRows.addView(buildCardDivider(activity));
            mSummaryRows.addView(buildUtilityRow(activity,
                    activity.getString(R.string.storage_incomplete_downloads,
                            formatBytes(activity, uiState.incompleteBytes)),
                    () -> clearIncompleteDownloads()));
        }
        mSummaryRows.addView(buildCardDivider(activity));
        mSummaryRows.addView(buildUtilityRow(activity,
                activity.getString(R.string.storage_app_cache,
                        formatBytes(activity, uiState.cacheBytes)),
                () -> clearAppCache()));
        mModelRows.removeAllViews();
        // the row map mirrors exactly what is rendered after this pass, so
        // stale progress events for removed rows find no view and are ignored
        mRowStatusViews.clear();
        Map<String, List<ModelStorageEntry>> groupedEntries =
                groupByFeatureLabel(uiState.entries);
        boolean firstGroup = true;
        for (Map.Entry<String, List<ModelStorageEntry>> groupEntry
                : groupedEntries.entrySet()) {
            // separator between feature GROUPS only (never between the rows
            // inside a group), so the group boundary reads clearly
            if (!firstGroup) {
                mModelRows.addView(buildGroupDivider(activity));
            }
            firstGroup = false;
            mModelRows.addView(buildGroupHeader(activity, groupEntry.getKey()));
            for (ModelStorageEntry modelEntry : groupEntry.getValue()) {
                mModelRows.addView(buildModelRow(activity, modelEntry));
            }
        }
    }

    /**
     * Group the model entries by their feature label, preserving the
     * catalog order of the snapshot inside each group (the groups appear
     * in first-appearance order)
     */
    private static Map<String, List<ModelStorageEntry>> groupByFeatureLabel(
            List<ModelStorageEntry> entries) {
        Map<String, List<ModelStorageEntry>> groupedEntries = new LinkedHashMap<>();
        for (ModelStorageEntry entry : entries) {
            List<ModelStorageEntry> group = groupedEntries.get(entry.featureLabel);
            if (group == null) {
                group = new ArrayList<>();
                groupedEntries.put(entry.featureLabel, group);
            }
            group.add(entry);
        }
        return groupedEntries;
    }

    /**
     * The summary card content, top to bottom: one row with the total
     * downloaded size and the "Delete all" borderless Material 3 text
     * button aligned to the row's end (accent-colored through the M3
     * textButtonStyle attribute, only shown when at least one model is on
     * the disk — the confirm dialog covers all of them), then the free
     * space line. The caller appends the divider and the cache rows below
     * (see renderState). No own horizontal padding: the card's 16dp inner
     * padding (page_storage.xml) provides the inset for everything inside
     */
    private View buildSummarySection(Activity activity, UiState uiState) {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        List<ModelStorageEntry> downloadedEntries = new ArrayList<>();
        long totalBytesAccumulator = 0L;
        for (ModelStorageEntry entry : uiState.entries) {
            if (entry.downloaded) {
                downloadedEntries.add(entry);
                totalBytesAccumulator += entry.sizeBytes;
            }
        }
        // captured by the click lambda below, must be effectively final
        final long totalBytes = totalBytesAccumulator;
        LinearLayout totalRow = new LinearLayout(activity);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView totalText = new TextView(activity);
        totalText.setText(activity.getString(R.string.storage_total_downloaded,
                formatBytes(activity, uiState.totalDownloadedBytes)));
        totalRow.addView(totalText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!downloadedEntries.isEmpty()) {
            // borderless accent-text look via the base-module theme overlay
            // (why the empty-parent overlay exists: see themes.xml)
            MaterialButton deleteAllButton = new MaterialButton(
                    new ContextThemeWrapper(activity,
                            m.co.rh.id.a_jarwis.base.R.style.Theme_Asnapblur_Overlay_TextButton),
                    null, com.google.android.material.R.attr.materialButtonStyle);
            deleteAllButton.setText(R.string.button_delete_all);
            deleteAllButton.setOnClickListener(v ->
                    confirmDeleteAll(activity, downloadedEntries, totalBytes));
            totalRow.addView(deleteAllButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        section.addView(totalRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView freeSpaceText = new TextView(activity);
        freeSpaceText.setText(activity.getString(R.string.storage_free_space,
                formatBytes(activity, uiState.freeSpaceBytes)));
        freeSpaceText.setPadding(0, dp(activity, 4), 0, 0);
        section.addView(freeSpaceText);
        return section;
    }

    /**
     * One labelled utility row inside the summary card (incomplete
     * downloads / app cache): label on the left, the clear action on the
     * right. No horizontal padding of its own, the card's 16dp inner
     * padding provides the inset
     */
    private View buildUtilityRow(Activity activity, String label,
                                 Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        TextView labelText = new TextView(activity);
        labelText.setText(label);
        row.addView(labelText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clearButton = new Button(activity,
                null, android.R.attr.borderlessButtonStyle);
        clearButton.setText(R.string.button_clear);
        clearButton.setOnClickListener(v -> action.run());
        row.addView(clearButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * Full-width separator between feature groups, reusing the EXACT divider
     * resource of the settings menu entries (page_settings.xml /
     * page_storage.xml LinearLayout divider): the same
     * {@link R.drawable#divider_vertical} shape (1dp thick,
     * daynight_gray_700_white) inset by {@link R.dimen#text_margin} on both
     * sides (the drawable-equivalent of the layout's android:dividerPadding).
     * The LinearLayout divider mechanism adds no vertical spacing of its
     * own, so the 8dp top/bottom margins here are the breathing room between
     * the groups
     */
    private View buildGroupDivider(Activity activity) {
        View divider = buildDivider(activity);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1));
        int inset = dp(activity, 16);
        dividerParams.setMargins(inset, dp(activity, 8), inset, dp(activity, 8));
        divider.setLayoutParams(dividerParams);
        return divider;
    }

    /**
     * The in-card variant of {@link #buildGroupDivider}: the same
     * divider drawable, thickness and 8dp vertical margins, but NO
     * horizontal inset — the summary card's own 16dp inner padding provides
     * the inset, so the line aligns exactly with the card's rows (a group
     * divider's text_margin inset inside the padded card would double the
     * inset and draw the line narrower than the text)
     */
    private View buildCardDivider(Activity activity) {
        View divider = buildDivider(activity);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1));
        dividerParams.setMargins(0, dp(activity, 8), 0, dp(activity, 8));
        divider.setLayoutParams(dividerParams);
        return divider;
    }

    /**
     * The bare divider line view: the settings menu's divider_vertical
     * drawable as the background, the caller supplies the LayoutParams
     */
    private View buildDivider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundResource(R.drawable.divider_vertical);
        return divider;
    }

    /**
     * Feature group header inside the models section. The 16dp top padding
     * sits above the divider's own 8dp bottom margin, so a group header
     * breathes 24dp from the previous group's last row, and the 4dp bottom
     * padding keeps the header tight to its first row
     */
    private View buildGroupHeader(Activity activity, String featureLabel) {
        TextView headerText = new TextView(activity);
        headerText.setText(featureLabel);
        headerText.setTypeface(headerText.getTypeface(), Typeface.BOLD);
        headerText.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 4));
        return headerText;
    }

    /**
     * One model row: display name + detail line (size or the not
     * downloaded/downloading state — not-downloaded rows carry the catalog's
     * approximate size hint — then the relative last-used hint), a delete
     * button only for downloaded models, a download button only for
     * not-downloaded models that are not already downloading (downloading
     * rows show the state line only). The row's status line is registered in
     * {@link #mRowStatusViews} for the live progress updates
     */
    @SuppressLint("SetTextI18n")
    private View buildModelRow(Activity activity, ModelStorageEntry entry) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = new TextView(activity);
        nameText.setText(entry.displayName);
        textColumn.addView(nameText);
        TextView detailText = new TextView(activity);
        CharSequence lastUsedSuffix = buildLastUsedSuffix(activity, entry);
        // both parts are localized separately (buildModelStateText /
        // buildLastUsedSuffix), the concatenated separator + suffix are
        // locale-neutral
        detailText.setText(buildModelStateText(activity, entry) + " • " + lastUsedSuffix);
        detailText.setTextAppearance(activity,
                android.R.style.TextAppearance_Material_Small);
        textColumn.addView(detailText);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (entry.downloaded) {
            ImageButton deleteButton = new ImageButton(activity,
                    null, android.R.attr.borderlessButtonStyle);
            deleteButton.setImageResource(R.drawable.ic_delete_black);
            deleteButton.setContentDescription(activity.getString(R.string.button_delete));
            deleteButton.setOnClickListener(v -> confirmDeleteModel(activity, entry));
            row.addView(deleteButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (!entry.downloading) {
            ImageButton downloadButton = new ImageButton(activity,
                    null, android.R.attr.borderlessButtonStyle);
            downloadButton.setImageResource(R.drawable.ic_download_black);
            downloadButton.setContentDescription(
                    activity.getString(R.string.storage_button_download));
            downloadButton.setOnClickListener(v -> confirmDownloadModel(activity, entry));
            row.addView(downloadButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mRowStatusViews.put(entry.modelId,
                new ModelRowStatus(detailText, lastUsedSuffix));
        return row;
    }

    /**
     * The state part of a model row's status line: the real on-disk size,
     * the downloading state, or the not-downloaded state with the catalog's
     * approximate size hint (e.g. "Not downloaded (~171 MB)", the "~"
     * prefix is part of the catalog value, no dedicated string needed)
     */
    private String buildModelStateText(Activity activity, ModelStorageEntry entry) {
        if (entry.downloading) {
            return activity.getString(R.string.storage_downloading);
        }
        if (entry.downloaded) {
            return formatBytes(activity, entry.sizeBytes);
        }
        String notDownloaded = activity.getString(R.string.storage_not_downloaded);
        if (entry.approxDisplaySize == null || entry.approxDisplaySize.isEmpty()) {
            return notDownloaded;
        }
        return notDownloaded + " (" + entry.approxDisplaySize + ")";
    }

    /**
     * The immutable last-used suffix of a model row's status line, captured
     * at row construction so the live progress updates can rebuild the whole
     * line (the last-used moment cannot change mid-download)
     */
    private CharSequence buildLastUsedSuffix(Activity activity, ModelStorageEntry entry) {
        if (entry.lastUsedAtMillis > 0) {
            return DateUtils.getRelativeTimeSpanString(activity,
                    entry.lastUsedAtMillis);
        }
        return activity.getString(R.string.storage_never_used);
    }

    /**
     * Confirm dialog of a single-model delete: names the freed size and the
     * feature that will re-download the model, and warns about the running
     * download when one is active
     */
    private void confirmDeleteModel(Activity activity, ModelStorageEntry entry) {
        String message = activity.getString(R.string.msg_storage_delete_confirm,
                formatBytes(activity, entry.sizeBytes), entry.featureLabel);
        if (entry.downloading) {
            message = message + "\n\n" + activity.getString(
                    R.string.msg_storage_delete_confirm_downloading);
        }
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_storage_delete_confirm,
                        entry.displayName))
                .setMessage(message)
                .setPositiveButton(R.string.button_delete,
                        (dialog, which) -> deleteModel(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Confirm dialog of the "Delete all" action, then deletes every
     * downloaded model sequentially on the io scheduler
     */
    private void confirmDeleteAll(Activity activity,
                                  List<ModelStorageEntry> downloadedEntries,
                                  long totalBytes) {
        if (downloadedEntries.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_storage_delete_all_confirm)
                .setMessage(activity.getString(R.string.msg_storage_delete_all_confirm,
                        formatBytes(activity, totalBytes)))
                .setPositiveButton(R.string.button_delete,
                        (dialog, which) -> deleteModels(downloadedEntries))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Delete one model on the io scheduler through the page's RxDisposer,
     * then reload (the reload re-renders the whole page with fresh state).
     * A failed delete must not stay silent: the user gets a toast on top of
     * the error log
     */
    private void deleteModel(ModelStorageEntry entry) {
        mRxDisposer.add("deleteModel_" + entry.modelId,
                Single.fromCallable(() ->
                                mModelStorageManager.deleteModel(entry.modelId))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((deleted, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to delete model "
                                        + entry.displayName, throwable);
                                showToast(R.string.msg_storage_delete_failed);
                            } else if (!deleted) {
                                mLogger.e(TAG, "Model file of "
                                        + entry.displayName
                                        + " is still present after the delete");
                                showToast(R.string.msg_storage_delete_failed);
                            }
                            loadState();
                        }));
    }

    /**
     * Delete the given downloaded models sequentially on the io scheduler
     * (a sequential sweep is fine: the delete is bounded by the ~3 s cancel
     * settle wait per model at worst), then reload. One toast covers the
     * whole sweep when anything failed
     */
    private void deleteModels(List<ModelStorageEntry> entries) {
        mRxDisposer.add("deleteModels",
                Single.fromCallable(() -> {
                    boolean allDeleted = true;
                    for (ModelStorageEntry entry : entries) {
                        allDeleted &= mModelStorageManager.deleteModel(entry.modelId);
                    }
                    return allDeleted;
                })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((allDeleted, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to delete all models",
                                        throwable);
                                showToast(R.string.msg_storage_delete_failed);
                            } else if (!allDeleted) {
                                mLogger.e(TAG, "One or more model files are"
                                        + " still present after delete all");
                                showToast(R.string.msg_storage_delete_failed);
                            }
                            loadState();
                        }));
    }

    /**
     * The shared failure toast of the delete/download actions (shown on the
     * main thread by the subscribe callbacks above), so a failed action is
     * never log-only
     */
    private void showToast(int messageResId) {
        Activity activity = mNavigator == null ? null : mNavigator.getActivity();
        if (activity != null) {
            Toast.makeText(activity, messageResId, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Confirm dialog of the download action (same style as the delete
     * confirm): names the model and states the approximate size that will be
     * downloaded once over the internet, plus where the progress shows
     */
    private void confirmDownloadModel(Activity activity, ModelStorageEntry entry) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_storage_download_confirm,
                        entry.displayName))
                .setMessage(activity.getString(R.string.msg_storage_download_confirm,
                        entry.approxDisplaySize))
                .setPositiveButton(R.string.storage_button_download,
                        (dialog, which) -> startDownload(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Enqueue the download on the io scheduler through the page's
     * RxDisposer (ModelStorageManager.startDownload is a no-op for already
     * pending/running work), then reload — the fresh snapshot reports the
     * work as ENQUEUED, so the row flips to "Downloading…" and its buttons
     * disappear
     */
    private void startDownload(ModelStorageEntry entry) {
        mRxDisposer.add("startDownload_" + entry.modelId,
                Single.fromCallable(() -> {
                    mModelStorageManager.startDownload(entry.modelId);
                    return true;
                })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((started, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to enqueue the download of "
                                        + entry.displayName, throwable);
                                showToast(R.string.msg_storage_download_failed);
                            }
                            loadState();
                        }));
    }

    /**
     * Sweep the partial {@code "<target>.tmp"} download files on the io
     * scheduler, then reload
     */
    private void clearIncompleteDownloads() {
        mRxDisposer.add("clearIncompleteDownloads",
                Single.fromCallable(
                                mModelStorageManager::deleteIncompleteDownloads)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((reclaimedBytes, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to clear the incomplete"
                                        + " downloads", throwable);
                            }
                            loadState();
                        }));
    }

    /**
     * Clear the app cache on the io scheduler (the logger file plus every
     * temp file under the FileHelper tmp root — deleteTempFiles with an
     * empty prefix matches every temp file name), then reload
     */
    private void clearAppCache() {
        mRxDisposer.add("clearAppCache",
                Single.fromCallable(() -> {
                    mFileHelper.clearLogFile();
                    mFileHelper.deleteTempFiles("");
                    return true;
                })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe((cleared, throwable) -> {
                            if (throwable != null) {
                                mLogger.e(TAG, "Failed to clear the app cache",
                                        throwable);
                            }
                            loadState();
                        }));
    }

    /**
     * Human readable size through the platform formatter (e.g. "12.3 MB"),
     * the presentation every row and confirm dialog uses
     */
    private static String formatBytes(Activity activity, long bytes) {
        return Formatter.formatShortFileSize(activity, bytes);
    }

    /**
     * Density-scaled dp to px for the programmatic paddings
     */
    private static int dp(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        mAppBarSV.dispose(activity);
        mAppBarSV = null;
        mSummaryRows = null;
        mModelRows = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mRxDisposer = null;
        mFileHelper = null;
        mModelStorageManager = null;
        mModelChangeNotifier = null;
        mRowStatusViews = null;
    }

    /**
     * Everything the page renders, built in one background pass
     */
    private static class UiState {
        long totalDownloadedBytes;
        long freeSpaceBytes;
        long incompleteBytes;
        long cacheBytes;
        List<ModelStorageEntry> entries;
    }

    /**
     * Per-rendered-row handle for the live download progress updates
     * (see {@link #buildLastUsedSuffix} for the immutable-suffix capture)
     */
    private static class ModelRowStatus {
        final TextView statusView;
        final CharSequence lastUsedSuffix;

        ModelRowStatus(TextView statusView, CharSequence lastUsedSuffix) {
            this.statusView = statusView;
            this.lastUsedSuffix = lastUsedSuffix;
        }
    }
}
