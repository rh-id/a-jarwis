package m.co.rh.id.a_jarwis.app.ui.page.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.component.EditorProgressDialog;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.UiUtils;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.SubjectSegEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavBackPressResult;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * Interactive background removal editor (a dedicated single-image workflow,
 * opened from its own home tile): segments the salient subject of the picked
 * photo with the IS-Net general-use model ({@link SubjectSegEngine}) and
 * makes everything else fully transparent (transparent PNG result for the
 * save/share export). Remove-background only — there is no mode toggle, no
 * blur mode and no strength slider (the portrait editor keeps those).
 * <p>
 * The preview segmentation runs at a downscaled preview resolution through
 * the engine's per-key mask cache, the save/share re-segment at the full
 * resolution before writing the result. The engine's subject gate
 * decides the preview verdict: when it reports no subject the preview shows
 * the original with a note and the save/share skip the export (the save
 * with a note, the share with a toast). The gate is the processor's
 * two-step rejection: the raw-signal contrast gate zeroes a shapeless
 * salience map (uniform gray, smooth gradients) BEFORE the min-max
 * normalization can inflate it into a full-contrast mask, then the area
 * check rejects a flat / near-flat model output.
 * <p>
 * MEMORY STRATEGY: the subject net is a SESSION-LIFETIME processor (the
 * issue-#4 spike measured a ~1.04 GB peak native footprint on the 2 GB
 * 32-bit tier and ~1.02 GB on the 3 GB 64-bit tier — OpenCV dnn retains the
 * whole workspace inside the net after its forwards), so this page releases
 * the engine processor on dispose
 * ({@code releaseAllMasks() + releaseProcessor()}) instead of keeping it for
 * the app lifetime, and the next editor session lazily re-parses the model
 * behind the bounded native-heap settle of
 * {@code MLEngineInstance.getSubjectSegProcessor()}. Consequently a
 * segmentation failure that reaches Java (the native dnn "Insufficient
 * memory" CvException, an OutOfMemoryError, the 2 GB tier's
 * reload-under-reclaim failure class) is a CAUGHT, toast-worthy failure —
 * the editor re-enables and stays usable (the MagicEraser self-heal
 * pattern). A native abort that never reaches Java is not covered by this.
 */
public class BackgroundRemovalEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "BackgroundRemovalEditorPage";

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "bgremoval_share_";
    /**
     * File name prefix of the private cache copy of the picked image, created
     * once at segmentation time because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "background_removal_source_";
    /**
     * The engine mask cache key of the single image (the engine keys its
     * masks per opaque cache key, this editor edits exactly one image)
     */
    private static final int MASK_CACHE_KEY = 0;

    /**
     * Serializable editor state of this page,
     * only holds serializable fields (strings, boxed types),
     * so it survives the navigator snapshot, never hold Uri/Bitmap here
     */
    private static class EditorState implements Serializable {
        private static final long serialVersionUID = 1L;

        private String mSourceUri;
        /**
         * true when the segmentation passed the engine's subject area gate,
         * false when it did not pass it or the segmentation failed, null
         * means the image has not been segmented yet
         */
        private Boolean mHasSubject;
        private boolean mHasUnsavedChanges;
    }

    /**
     * Display result of the editor, never serialized (bitmaps must not be in
     * the navigator snapshot)
     */
    private static class ResultCache {
        private final boolean mHasSubject;
        private final Bitmap mResult;

        private ResultCache(boolean hasSubject, Bitmap result) {
            mHasSubject = hasSubject;
            mResult = result;
        }
    }

    /**
     * Segmentation state, used while the segmentation pipeline is running
     */
    private static class SegmentationResult {
        /**
         * Private cache copy of the source image created during segmentation
         * when the source uri is not a file uri, null when the source is
         * already a file uri or the copy failed (the original uri keeps
         * being used)
         */
        private File mSourceFile;
        private boolean mHasSubject;
    }

    /**
     * Save result of the save pipeline
     */
    private static class SaveResult {
        private static final int STATUS_SAVED = 1;
        private static final int STATUS_FAILED = 2;
        private static final int STATUS_NO_SUBJECT = 3;

        private int mStatus;
    }

    /**
     * Result of the share pipeline: the share temp file, or null when the
     * export was skipped (the image did not pass the subject gate)
     */
    private static class ShareResult {
        private final File mFile;

        private ShareResult(File file) {
            mFile = file;
        }
    }

    @NavInject
    private transient NavRoute mNavRoute;
    @NavInject
    private transient INavigator mNavigator;

    private final SerialBehaviorSubject<EditorState> mEditorState;

    // component
    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient SubjectSegEngine mSubjectSegEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient AppCompatTextView mTextNoSubject;
    /**
     * Busy-guard progress dialog of the editor pipelines (see
     * {@link EditorProgressDialog}), its isWorking is the gate every
     * pipeline relies on
     */
    private transient EditorProgressDialog mProgressDialog;

    // runtime
    private transient ResultCache mResultCache;
    /**
     * The composited bitmap currently shown on mImageView, may be the
     * ResultCache bitmap or the raw preview of a no-subject image
     */
    private transient Bitmap mDisplayBitmap;
    private transient boolean mIsSegmenting;
    private transient boolean mNoSubjectToastShown;
    private transient SaveResult mSaveResult;
    private transient File mLastShareFile;
    /**
     * Private cache copies of the picked image created by this session,
     * never serialized. Deleted on dispose, a process death leaves them in
     * place so the restored snapshot can still read them (stale leftovers
     * are purged by age in a later session)
     */
    private transient ArrayList<File> mSessionSourceFiles;

    public BackgroundRemovalEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mSubjectSegEngine = mSvProvider.get(SubjectSegEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level source copy list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(
                R.layout.page_background_removal_editor, container, false);
        // the busy-guard progress dialog component is created before the
        // first pipeline can start (the segmentation below shows it)
        mProgressDialog = new EditorProgressDialog(activity,
                R.string.title_background_removal);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            NavBackPressResult result = handleBack();
            if (result != null && result.shouldPop()) {
                mNavigator.pop();
            }
        });
        mImageView = rootView.findViewById(R.id.imageView);
        mTextNoSubject = rootView.findViewById(R.id.text_no_subject);
        View saveButton = rootView.findViewById(R.id.button_save);
        saveButton.setOnClickListener(this);
        View shareButton = rootView.findViewById(R.id.button_share);
        shareButton.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        if (mEditorState.getValue() == null) {
            // first open, parse the picked image uri
            mEditorState.onNext(parseRouteArgs());
        }
        // segment when the subject state has not been decided yet
        // (mHasSubject == null), on restore (e.g. process death in the middle
        // of the segmentation) the pending state re-segments, a decided state
        // goes straight to the display pipeline (the engine mask cache is
        // empty then, the display re-segments just in time)
        startSegmentation(mEditorState.getValue());
        // best effort purge of the temp files left over by previous sessions,
        // only files older than the cutoff are deleted so a restored session
        // keeps the source copies its snapshot points to
        mRxDisposer.add("createView_purgeTempFiles", Single.fromCallable(() -> {
                    long maxAgeMillis = TimeUnit.HOURS.toMillis(1);
                    mFileHelper.deleteTempFiles(EDITOR_SOURCE_PREFIX, maxAgeMillis);
                    mFileHelper.deleteTempFiles(SHARE_FILE_PREFIX, maxAgeMillis);
                    return true;
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .subscribe((aBoolean, throwable) -> {
                    // any failure is ignored
                }));
        return rootView;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        if (mProgressDialog != null) {
            mProgressDialog.dispose();
            mProgressDialog = null;
        }
        if (mResultCache != null && mResultCache.mResult != null
                && mResultCache.mResult != mDisplayBitmap) {
            mResultCache.mResult.recycle();
        }
        mResultCache = null;
        if (mDisplayBitmap != null) {
            mDisplayBitmap.recycle();
            mDisplayBitmap = null;
        }
        if (mSubjectSegEngine != null) {
            // the session masks are released so the same keys of a later
            // session never read the masks of this session's image
            mSubjectSegEngine.releaseAllMasks();
            // SESSION-LIFETIME LAW (see the class javadoc): the subject
            // net's ~1 GB dnn workspace must not outlive the editor
            // session, the next session re-parses the model behind the
            // bounded native-heap settle
            mSubjectSegEngine.releaseProcessor();
        }
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        if (mSessionSourceFiles != null) {
            // best effort deletion of the source copies owned by this session,
            // nothing reads them once the editor page is popped. The list is kept
            // (cleared only) so a straggling segmentation emission can never NPE
            for (File sourceFile : mSessionSourceFiles) {
                sourceFile.delete();
            }
            mSessionSourceFiles.clear();
        }
        mImageView = null;
        mTextNoSubject = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mSubjectSegEngine = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_save) {
            saveImage();
        } else if (id == R.id.button_share) {
            shareImage();
        }
    }

    @Override
    public NavBackPressResult onBackPressed(View currentView, Activity activity, INavigator navigator) {
        return handleBack();
    }

    private EditorState parseRouteArgs() {
        EditorState editorState = new EditorState();
        if (mNavRoute != null && mNavRoute.getRouteArgs() instanceof UriList) {
            ArrayList<String> uris = ((UriList) mNavRoute.getRouteArgs()).getUris();
            if (!uris.isEmpty()) {
                // single image flow, only the first picked uri is taken
                editorState.mSourceUri = uris.get(0);
            }
        }
        return editorState;
    }

    private void onEditorStateChanged(EditorState editorState) {
        if (mIsSegmenting) {
            // the segmentation pipeline shows the progress dialog and the
            // image is displayed when it finishes
            return;
        }
        displayResult(editorState);
    }

    private void displayResult(EditorState editorState) {
        if (editorState.mSourceUri == null) {
            return;
        }
        Boolean hasSubject = editorState.mHasSubject;
        if (hasSubject == null) {
            // only reachable when the createView segmentation completed
            // without deciding the state, retry the segmentation then
            startSegmentation(editorState);
            return;
        }
        ResultCache resultCache = mResultCache;
        if (resultCache != null && resultCache.mHasSubject == hasSubject) {
            showResult(resultCache.mResult);
            updateNoSubjectNote(hasSubject);
            return;
        }
        prepareResult(editorState);
    }

    /**
     * Segment the picked image when its subject state has not been decided
     * yet, runs the ISNet inference at the preview resolution and caches the
     * subject mask in the engine (keyed by {@link #MASK_CACHE_KEY})
     */
    private void startSegmentation(EditorState editorState) {
        if (editorState.mSourceUri == null || editorState.mHasSubject != null) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mIsSegmenting = true;
        mProgressDialog.show(false, 0,
                activity.getString(R.string.detecting_subject_progress));
        mRxDisposer.add("startSegmentation", Single.fromCallable(() ->
                        segmentationTask(editorState))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((segmentationResult, throwable) -> {
                    mIsSegmenting = false;
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // a failed segmentation (typically the native dnn
                        // "Insufficient memory" CvException on a fragmented
                        // 32-bit address space) must not crash: the editor
                        // stays usable with the original image shown
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        displayResult(mEditorState.getValue());
                        return;
                    }
                    onSegmented(mEditorState.getValue(), segmentationResult);
                }));
    }

    /**
     * Decode the downscaled preview of the picked image and segment its
     * subject. Runs on a background thread.
     */
    private SegmentationResult segmentationTask(EditorState editorState) {
        SegmentationResult segmentationResult = new SegmentationResult();
        Bitmap preview = null;
        try {
            Uri sourceUri = Uri.parse(editorState.mSourceUri);
            // content uri grants are ephemeral and die with the process, copy the
            // image to a private cache file once so the editor still works after a
            // process restart (the snapshot persists the file uri, see onSegmented)
            if (!"file".equals(sourceUri.getScheme())) {
                segmentationResult.mSourceFile = mFileHelper.copySourceToTempFile(
                        EDITOR_SOURCE_PREFIX + System.currentTimeMillis() + ".jpg",
                        sourceUri);
            }
            Uri uri = segmentationResult.mSourceFile != null
                    ? Uri.fromFile(segmentationResult.mSourceFile) : sourceUri;
            preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
            // the mask is cached under MASK_CACHE_KEY inside the engine, the
            // display pipeline reuses it instead of re-running the inference
            segmentationResult.mHasSubject = mSubjectSegEngine.prepareSubject(
                    MASK_CACHE_KEY, preview);
        } catch (Exception | OutOfMemoryError e) {
            // the segmentation failing (e.g. the OOM toast class) must never
            // crash the editor: the persisted hasSubject=false marks the
            // image as below the subject gate so it shows the original and
            // save/share skip the export
            segmentationResult.mHasSubject = Boolean.FALSE;
            mLogger.e(TAG, e.getMessage(), e);
        } finally {
            if (preview != null) {
                preview.recycle();
            }
        }
        return segmentationResult;
    }

    private void onSegmented(EditorState editorState,
                             SegmentationResult segmentationResult) {
        editorState.mHasSubject = segmentationResult.mHasSubject;
        if (segmentationResult.mHasSubject) {
            // the derived transparent result is the unsaved change
            editorState.mHasUnsavedChanges = true;
        } else {
            // surface the no-subject verdict immediately, the persistent
            // note explains it on the display
            if (!mNoSubjectToastShown) {
                mNoSubjectToastShown = true;
                Toast.makeText(mNavigator.getActivity(),
                        R.string.no_subject_detected, Toast.LENGTH_LONG).show();
            }
        }
        if (segmentationResult.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri on
            // the main thread before the state emission, so the snapshot
            // persists the file uri and every later step (preview rebuild,
            // save, share, restore) reads the copy
            editorState.mSourceUri = Uri.fromFile(
                    segmentationResult.mSourceFile).toString();
            if (mSessionSourceFiles == null) {
                // belt-and-braces against a deserialized instance (the list
                // is normally re-initialized in provideComponent)
                mSessionSourceFiles = new ArrayList<>();
            }
            mSessionSourceFiles.add(segmentationResult.mSourceFile);
        }
        mEditorState.onNext(editorState);
    }

    /**
     * Composite the displayed image at the preview resolution, a rebuild only
     * re-decodes the preview and re-composites (the mask stays engine-cached,
     * a cache miss — e.g. after a process death restore — re-segments just in
     * time inside the engine), the inference is not re-run for the common path
     */
    private void prepareResult(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        mRxDisposer.add("prepareResult", Single.fromCallable(() ->
                        buildResult(editorState))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((resultCache, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // a failed composite must not crash: keep the editor
                        // usable (the retry happens on the next display pass)
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (mResultCache != null && mResultCache.mResult != resultCache.mResult) {
                        mResultCache.mResult.recycle();
                    }
                    mResultCache = resultCache;
                    showResult(resultCache.mResult);
                    updateNoSubjectNote(resultCache.mHasSubject);
                }));
    }

    /**
     * Build the display result. Runs on a background thread: decodes the
     * preview and removes the background through the engine-cached mask (the
     * mask never leaves the engine, it is segmented just in time when the
     * cache has no entry yet). A no-subject image displays the original
     * untouched preview.
     */
    private ResultCache buildResult(EditorState editorState) throws IOException {
        Uri uri = Uri.parse(editorState.mSourceUri);
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        try {
            boolean hasSubject = Boolean.TRUE.equals(editorState.mHasSubject);
            Bitmap result;
            if (!hasSubject) {
                // segmented as no-subject (or the segmentation failed), the
                // display shows the original untouched preview, the note
                // explains
                result = preview;
                preview = null; // the ownership moved to the ResultCache
            } else {
                result = mSubjectSegEngine.removeBackground(MASK_CACHE_KEY, preview);
                preview.recycle();
                preview = null;
            }
            return new ResultCache(hasSubject, result);
        } catch (Exception | OutOfMemoryError e) {
            if (preview != null) {
                preview.recycle();
            }
            throw e;
        }
    }

    /**
     * Swap the bitmap currently shown on the image view.
     * Runs on the main thread, the bitmap lifetime is never shared with the
     * background threads.
     */
    private void showResult(Bitmap result) {
        if (mDisplayBitmap != null && mDisplayBitmap != result) {
            mDisplayBitmap.recycle();
        }
        mDisplayBitmap = result;
        mImageView.setImageBitmap(result);
        mImageView.invalidate();
    }

    private void updateNoSubjectNote(Boolean hasSubject) {
        if (mTextNoSubject == null) {
            return;
        }
        if (Boolean.FALSE.equals(hasSubject)) {
            mTextNoSubject.setVisibility(View.VISIBLE);
        } else {
            mTextNoSubject.setVisibility(View.GONE);
        }
    }

    private NavBackPressResult handleBack() {
        if (mProgressDialog.isWorking()) {
            // a segmentation/prepare/save is running, the back press is ignored
            return NavBackPressResult.noPop();
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_background_removal_changes)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return NavBackPressResult.noPop();
        }
        return NavBackPressResult.pop();
    }

    private void saveImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mSaveResult = new SaveResult();
        mProgressDialog.show(false, 0,
                activity.getString(R.string.saving_progress, 1, 1));
        mRxDisposer.add("saveImage", Single.fromCallable(this::saveImageTask)
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((saveResult, throwable) -> {
                    mProgressDialog.hide();
                    // on a throwable RxJava delivers a null result, so the
                    // field is synced from a real emission only (it keeps
                    // the pre-initialized instance otherwise, the dialog
                    // reads the status through it)
                    if (saveResult != null) {
                        mSaveResult = saveResult;
                    }
                    if (throwable != null) {
                        // a failed save (e.g. the full resolution segmentation
                        // OOM on the memory-constrained tiers) must not crash
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        mSaveResult.mStatus = SaveResult.STATUS_FAILED;
                    }
                    if (mSaveResult.mStatus == SaveResult.STATUS_SAVED) {
                        EditorState editorState = mEditorState.getValue();
                        if (editorState != null && editorState.mHasUnsavedChanges) {
                            editorState.mHasUnsavedChanges = false;
                            mEditorState.onNext(editorState);
                        }
                    }
                    showSaveSummary(activity);
                }));
    }

    /**
     * Save the image to the gallery as a transparent PNG. The subject mask is
     * computed at the full resolution (never serialized, always rebuilt), the
     * subject gate decides between the export and the no-subject skip. Runs
     * on a background thread.
     */
    @SuppressLint("MissingPermission")
    private SaveResult saveImageTask() {
        SaveResult saveResult = new SaveResult();
        try {
            EditorState editorState = mEditorState.getValue();
            if (Boolean.FALSE.equals(editorState.mHasSubject)) {
                // the preview already failed the subject gate: the full
                // resolution run would fail it too, or export a cut-out the
                // user never saw, so the heavy inference is skipped
                saveResult.mStatus = SaveResult.STATUS_NO_SUBJECT;
                return saveResult;
            }
            Uri uri = Uri.parse(editorState.mSourceUri);
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                // the full resolution mask is rebuilt (the cached preview
                // mask is the wrong resolution), it is never worth caching
                resultBitmap = mSubjectSegEngine.removeBackground(fullBitmap);
                if (resultBitmap == null) {
                    // the full resolution mask failed the subject gate: no export
                    saveResult.mStatus = SaveResult.STATUS_NO_SUBJECT;
                    return saveResult;
                }
                String title = buildSavedImageTitle();
                String stringUrl = mMediaHelper.insertImage(resultBitmap, title,
                        title, Bitmap.CompressFormat.PNG);
                if (stringUrl == null) {
                    saveResult.mStatus = SaveResult.STATUS_FAILED;
                } else {
                    saveResult.mStatus = SaveResult.STATUS_SAVED;
                }
            } finally {
                fullBitmap.recycle();
                if (resultBitmap != null) {
                    resultBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            saveResult.mStatus = SaveResult.STATUS_FAILED;
        }
        return saveResult;
    }

    /**
     * @return the gallery file title of the saved image, always PNG so the
     * media store stores the alpha channel with the matching mime
     */
    private String buildSavedImageTitle() {
        return "bgremoval_" + System.currentTimeMillis() + ".png";
    }

    private void showSaveSummary(Activity activity) {
        StringBuilder message = new StringBuilder();
        SaveResult saveResult = mSaveResult;
        if (saveResult.mStatus == SaveResult.STATUS_SAVED) {
            message.append(activity.getString(R.string.saved_summary, 1, 1));
        } else if (saveResult.mStatus == SaveResult.STATUS_NO_SUBJECT) {
            message.append(activity.getString(R.string.no_subject_detected));
        } else {
            message.append(activity.getString(R.string.failed_to_save_,
                    activity.getString(R.string.title_background_removal)));
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_save_result)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (saveResult.mStatus == SaveResult.STATUS_SAVED) {
                        // the result is persisted, leave the editor
                        mNavigator.pop();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void shareImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.preparing_share));
        mRxDisposer.add("shareImage", Single.fromCallable(this::buildShareResult)
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((shareResult, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // a failed share (e.g. the full resolution
                        // segmentation OOM) must not crash
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    File file = shareResult.mFile;
                    if (file == null) {
                        // the export was skipped: the image did not pass the
                        // subject gate
                        Toast.makeText(activity, R.string.no_subject_detected,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    mLastShareFile = file;
                    UiUtils.shareFile(activity, file,
                            activity.getString(R.string.share), "image/png");
                }));
    }

    /**
     * Composite the image at the full resolution and write it to a temp file
     * to be shared (transparent PNG).
     *
     * @return the share result, never null: its file is null when the
     * export was skipped (the preview verdict was "no subject", or the full
     * resolution mask failed the subject gate). The outcome travels in a
     * holder because a null Callable result would reach the subscriber as
     * an error, not as the skip outcome. Runs on a background thread.
     */
    private ShareResult buildShareResult() {
        try {
            EditorState editorState = mEditorState.getValue();
            if (Boolean.FALSE.equals(editorState.mHasSubject)) {
                // the preview already failed the subject gate: skip the
                // heavy full resolution run, like the save path does
                return new ShareResult(null);
            }
            Uri uri = Uri.parse(editorState.mSourceUri);
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                // the full resolution mask is rebuilt for every share
                resultBitmap = mSubjectSegEngine.removeBackground(fullBitmap);
                if (resultBitmap == null) {
                    // the full resolution mask failed the subject gate: no export
                    return new ShareResult(null);
                }
                return new ShareResult(mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis() + ".png",
                        resultBitmap, Bitmap.CompressFormat.PNG));
            } finally {
                fullBitmap.recycle();
                if (resultBitmap != null) {
                    resultBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError) instead of
            // delivering them to the error consumer, wrap it into a runtime exception
            // so it is handled as a share failure
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
