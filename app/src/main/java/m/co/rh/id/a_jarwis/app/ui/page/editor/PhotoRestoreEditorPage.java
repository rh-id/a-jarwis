package m.co.rh.id.a_jarwis.app.ui.page.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import m.co.rh.id.a_jarwis.ml_engine.model.ProgressListener;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.PhotoRestoreEngine;
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
 * Interactive photo restore (NAFNet deblur) editor: capped preview deblur,
 * before/after toggle, Save/Share tile-deblur the FULL-resolution original.
 * The net is app-lifetime (teardown-poison note on {@link NafNetProcessor})
 * — this page never releases it.
 */
public class PhotoRestoreEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "PhotoRestoreEditorPage";

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * Ceiling of the tiled deblur tile side: tileSize = min(workingCap, this)
     */
    private static final int TILE_SIZE_CEILING = 512;
    /**
     * Divisor of the tiled overlap band: overlap = tileSize / this
     */
    private static final int TILE_OVERLAP_DIVISOR = 6;
    /**
     * File name prefix of the temp file created for sharing a deblurred image
     */
    private static final String SHARE_FILE_PREFIX = "restore_share_";
    /**
     * File name prefix of the private cache copy of the picked image
     */
    private static final String RESTORE_SOURCE_PREFIX = "restore_source_";
    /**
     * File name prefix of the persisted preview deblur result file
     */
    private static final String DEBLUR_FILE_PREFIX = "restore_deblur_";

    /**
     * Serializable editor state (no Uri/Bitmap); paths into cache temp files.
     */
    private static class EditorState implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * The picked image uri (string form), read until the copy exists
         */
        private String mPickedUri;
        /**
         * Absolute path of the private cache copy of the picked image
         */
        private String mSourceTempPath;
        /**
         * Absolute path of the persisted preview deblur result (null = none)
         */
        private String mDeblurredTempPath;
        /**
         * true when the deblurred preview is shown (needs mDeblurredTempPath)
         */
        private boolean mShowingDeblurred;
        /**
         * Preview dimension of the loaded original, zero means not loaded
         */
        private int mPreviewWidth;
        private int mPreviewHeight;
        private boolean mHasUnsavedChanges;
    }

    /**
     * Result of the initial source load (private copy + preview decode)
     */
    private static class LoadResult {
        /**
         * Private cache copy of the source image, null when the copy failed
         */
        private final File mSourceFile;
        /**
         * The decoded downscaled preview of the source
         */
        private final Bitmap mPreview;

        private LoadResult(File sourceFile, Bitmap preview) {
            mSourceFile = sourceFile;
            mPreview = preview;
        }
    }

    /**
     * Result of the preview deblur pipeline (bitmap + persisted temp file)
     */
    private static class DeblurPreviewResult {
        private final File mDeblurredFile;
        private final Bitmap mDeblurredBitmap;

        private DeblurPreviewResult(File deblurredFile, Bitmap deblurredBitmap) {
            mDeblurredFile = deblurredFile;
            mDeblurredBitmap = deblurredBitmap;
        }
    }

    /**
     * Result of the process-death restore preview decode (original + deblurred)
     */
    private static class RestorePreviewResult {
        private Bitmap mOriginal;
        private Bitmap mDeblurred;

        private RestorePreviewResult(Bitmap original, Bitmap deblurred) {
            mOriginal = original;
            mDeblurred = deblurred;
        }
    }

    @NavInject
    private transient NavRoute mNavRoute;
    @NavInject
    private transient INavigator mNavigator;

    private final SerialBehaviorSubject<EditorState> mEditorState;

    // component
    private transient Provider mSvProvider;
    private transient Context mAppContext;
    private transient ILogger mLogger;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient PhotoRestoreEngine mPhotoRestoreEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;
    /**
     * Main-thread handler for the tiled deblur progress hand-off
     */
    private transient Handler mMainHandler;

    // view
    private transient AppCompatImageView mImageView;
    private transient MaterialButton mButtonToggleBeforeAfter;
    private transient View mButtonDeblur;
    private transient View mButtonSave;
    private transient View mButtonShare;
    /**
     * Busy-guard progress dialog, its isWorking gates every pipeline
     */
    private transient EditorProgressDialog mProgressDialog;

    // runtime
    /**
     * The original preview bitmap, the "before" side of the toggle
     */
    private transient Bitmap mPreviewOriginal;
    /**
     * The deblurred preview bitmap, null until the first deblur succeeded
     */
    private transient Bitmap mPreviewDeblurred;
    private transient File mLastShareFile;
    /**
     * Session private cache files (deleted on dispose, age-purged leftovers)
     */
    private transient List<File> mSessionFiles;
    /**
     * Set first in dispose: tasks check it under the mSessionFiles monitor
     */
    private transient volatile boolean mIsDisposed;

    public PhotoRestoreEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionFiles = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mAppContext = mSvProvider.getContext().getApplicationContext();
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mPhotoRestoreEngine = mSvProvider.get(PhotoRestoreEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        mMainHandler = new Handler(Looper.getMainLooper());
        // session-level temp file list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionFiles = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_photo_restore_editor, container, false);
        // the busy-guard progress dialog component is created before the
        // first pipeline can start (the preview load below shows it)
        mProgressDialog = new EditorProgressDialog(activity, R.string.photo_restore_title);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            NavBackPressResult result = handleBack();
            if (result != null && result.shouldPop()) {
                mNavigator.pop();
            }
        });
        mImageView = rootView.findViewById(R.id.imageView);
        mButtonToggleBeforeAfter = rootView.findViewById(R.id.button_toggle_before_after);
        mButtonToggleBeforeAfter.setOnClickListener(this);
        mButtonDeblur = rootView.findViewById(R.id.button_deblur);
        mButtonDeblur.setOnClickListener(this);
        mButtonSave = rootView.findViewById(R.id.button_save);
        mButtonSave.setOnClickListener(this);
        mButtonShare = rootView.findViewById(R.id.button_share);
        mButtonShare.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            // first open, parse the picked image uri and start the initial
            // load (private source copy + preview decode)
            editorState = parseRouteArgs();
            mEditorState.onNext(editorState);
            startInitialLoad(editorState);
        } else {
            // process death restore: re-decode both previews from the
            // persisted private temp files behind the busy-guard
            restorePreviews(editorState);
        }
        updateActionControls(mEditorState.getValue());
        // best effort purge of the temp files left over by previous sessions,
        // only files older than the cutoff are deleted so a restored session
        // keeps the temp files its snapshot points to
        mRxDisposer.add("createView_purgeTempFiles", Single.fromCallable(() -> {
                    long maxAgeMillis = TimeUnit.HOURS.toMillis(1);
                    mFileHelper.deleteTempFiles(RESTORE_SOURCE_PREFIX, maxAgeMillis);
                    mFileHelper.deleteTempFiles(DEBLUR_FILE_PREFIX, maxAgeMillis);
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
        // flagged before any cleanup: the load/deblur tasks register their
        // files under the same monitor this flag is checked with (see
        // registerSessionFile), so nothing created from here on can outlive
        // the session cleanup below
        mIsDisposed = true;
        if (mProgressDialog != null) {
            mProgressDialog.dispose();
            mProgressDialog = null;
        }
        if (mPreviewOriginal != null) {
            mPreviewOriginal.recycle();
            mPreviewOriginal = null;
        }
        if (mPreviewDeblurred != null) {
            mPreviewDeblurred.recycle();
            mPreviewDeblurred = null;
        }
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        if (mSessionFiles != null) {
            // best effort deletion of the temp files owned by this session,
            // nothing reads them once the editor page is popped. The list is
            // kept (cleared only) so a straggling emission can never NPE
            synchronized (mSessionFiles) {
                for (File sessionFile : mSessionFiles) {
                    sessionFile.delete();
                }
                mSessionFiles.clear();
            }
        }
        // NOTE (deliberate, do not "fix"): there is NO PhotoRestoreEngine/net
        // release here, unlike the SAM/LaMa/AiDetect editors. The NAFNet
        // net is app-lifetime cached (see MLEngineInstance): releasing it
        // would poison every later net in the process (measured OpenCV dnn
        // SEGV), so the cached net simply survives this page's disposal.
        mImageView = null;
        mButtonToggleBeforeAfter = null;
        mButtonDeblur = null;
        mButtonSave = null;
        mButtonShare = null;
        mMainHandler = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mAppContext = null;
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mPhotoRestoreEngine = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
    }

    /**
     * Register a temp file into the session list (same monitor as dispose).
     *
     * @return false when the page is disposing: the file was deleted here
     */
    private boolean registerSessionFile(File sessionFile) {
        if (mSessionFiles == null) {
            sessionFile.delete();
            return false;
        }
        synchronized (mSessionFiles) {
            if (mIsDisposed) {
                sessionFile.delete();
                return false;
            }
            mSessionFiles.add(sessionFile);
            return true;
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_toggle_before_after) {
            toggleBeforeAfter();
        } else if (id == R.id.button_deblur) {
            deblurPreview();
        } else if (id == R.id.button_save) {
            saveImage();
        } else if (id == R.id.button_share) {
            shareImage();
        }
    }

    @Override
    public NavBackPressResult onBackPressed(View currentView, Activity activity, INavigator navigator) {
        return handleBack();
    }

    /**
     * Working cap: flat 512 until the RAM tiers are device-validated (~8.3 KB/px)
     */
    private static int suggestedPhotoRestoreWorkingCap(Context context) {
        return 512;
    }

    /**
     * Tile size of the full-resolution pipelines: min(workingCap, TILE_SIZE_CEILING)
     */
    private int fullResTileSize() {
        return Math.min(suggestedPhotoRestoreWorkingCap(mAppContext),
                TILE_SIZE_CEILING);
    }

    private EditorState parseRouteArgs() {
        EditorState editorState = new EditorState();
        if (mNavRoute != null && mNavRoute.getRouteArgs() instanceof UriList) {
            ArrayList<String> uris = ((UriList) mNavRoute.getRouteArgs()).getUris();
            if (!uris.isEmpty()) {
                // the restore editor works on a single image, only the
                // first picked uri is used
                editorState.mPickedUri = uris.get(0);
            }
        }
        return editorState;
    }

    /**
     * The source uri: private cache copy if present, else the picked uri
     */
    private Uri readSourceUri(EditorState editorState) {
        if (editorState.mSourceTempPath != null) {
            return Uri.fromFile(new File(editorState.mSourceTempPath));
        }
        if (editorState.mPickedUri == null) {
            return null;
        }
        return Uri.parse(editorState.mPickedUri);
    }

    private void onEditorStateChanged(EditorState editorState) {
        updateActionControls(editorState);
        showCurrent(editorState);
    }

    /**
     * Show the state's preview (deblurred when toggled, else original)
     */
    private void showCurrent(EditorState editorState) {
        if (mImageView == null) {
            return;
        }
        Bitmap toShow = editorState.mShowingDeblurred && mPreviewDeblurred != null
                ? mPreviewDeblurred : mPreviewOriginal;
        if (toShow == null) {
            // nothing loaded yet (the load pipeline is still running or
            // failed), the image view stays empty until a decode succeeds
            return;
        }
        mImageView.setImageBitmap(toShow);
        mImageView.invalidate();
    }

    /**
     * Reflect the state in the buttons (need a loaded preview and a free guard)
     */
    private void updateActionControls(EditorState editorState) {
        if (mButtonToggleBeforeAfter == null || mButtonDeblur == null
                || mButtonSave == null || mButtonShare == null) {
            return;
        }
        boolean working = mProgressDialog != null && mProgressDialog.isWorking();
        boolean loaded = editorState.mPreviewWidth > 0;
        boolean hasDeblurred = editorState.mDeblurredTempPath != null;
        mButtonToggleBeforeAfter.setVisibility(
                hasDeblurred ? View.VISIBLE : View.GONE);
        mButtonToggleBeforeAfter.setText(
                editorState.mShowingDeblurred ? R.string.photo_restore_show_original
                        : R.string.photo_restore_show_deblurred);
        mButtonToggleBeforeAfter.setEnabled(!working);
        mButtonDeblur.setEnabled(loaded && !working);
        mButtonSave.setEnabled(loaded && !working);
        mButtonShare.setEnabled(loaded && !working);
    }

    /**
     * Copy the picked image to a cache file, decode its preview (bitmap parked)
     */
    private void startInitialLoad(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<LoadResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("startInitialLoad", Single.fromCallable(() -> {
                    // prefer the surviving private copy: after a process
                    // death the photo-picker uri grant is gone (measured
                    // SecurityException 2026-11-18), the temp file is the
                    // only readable source
                    LoadResult loadResult = loadTask(editorState.mSourceTempPath != null
                            ? Uri.fromFile(new File(editorState.mSourceTempPath)).toString()
                            : editorState.mPickedUri);
                    pendingResult.set(loadResult);
                    if (disposed.get()
                            && pendingResult.compareAndSet(loadResult, null)) {
                        recycleLoadResult(loadResult);
                    }
                    return loadResult;
                })
                .doOnDispose(() -> {
                    disposed.set(true);
                    if (!delivered.get()) {
                        recycleLoadResult(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((loadResult, throwable) -> {
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.failed_to_load_image,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (currentState == null) {
                        return;
                    }
                    if (loadResult.mSourceFile != null) {
                        // persist the private copy path so the snapshot
                        // survives a process death (content uri grants do
                        // not)
                        currentState.mSourceTempPath =
                                loadResult.mSourceFile.getAbsolutePath();
                    }
                    currentState.mPreviewWidth = loadResult.mPreview.getWidth();
                    currentState.mPreviewHeight = loadResult.mPreview.getHeight();
                    if (mPreviewOriginal != null
                            && mPreviewOriginal != loadResult.mPreview) {
                        mPreviewOriginal.recycle();
                    }
                    mPreviewOriginal = loadResult.mPreview;
                    mEditorState.onNext(currentState);
                }));
    }

    /**
     * Copy the source to a cache file and decode its preview (ownership to caller)
     */
    private LoadResult loadTask(String pickedUri) throws IOException {
        File sourceFile = null;
        if (pickedUri != null && !pickedUri.startsWith("file:")) {
            // only a content (picker) uri needs the private copy — a
            // file: uri is our own surviving temp file from an earlier
            // session, reading it needs no grant
            sourceFile = mFileHelper.copySourceToTempFile(
                    RESTORE_SOURCE_PREFIX + 0 + ".jpg", Uri.parse(pickedUri));
            if (sourceFile != null
                    && !registerSessionFile(sourceFile)) {
                // the page was disposed before this file could be
                // registered, it was deleted by the registration loser —
                // fall back to the picked uri (the Rx chain never delivers
                // to the disposed page anyway)
                sourceFile = null;
            }
        }
        Uri readUri = sourceFile != null
                ? Uri.fromFile(sourceFile) : Uri.parse(pickedUri);
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(readUri,
                PREVIEW_MAX_DIM);
        return new LoadResult(sourceFile, preview);
    }

    /**
     * Release a load result nobody delivered (page disposed mid-load)
     */
    private static void recycleLoadResult(LoadResult loadResult) {
        if (loadResult == null) {
            return;
        }
        if (loadResult.mPreview != null) {
            loadResult.mPreview.recycle();
        }
        if (loadResult.mSourceFile != null) {
            loadResult.mSourceFile.delete();
        }
    }

    /**
     * Process-death restore: re-decode both previews (failed decodes are toasts)
     */
    private void restorePreviews(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<RestorePreviewResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("restorePreviews", Single.fromCallable(() -> {
                    RestorePreviewResult result = restoreTask(editorState);
                    pendingResult.set(result);
                    if (disposed.get()
                            && pendingResult.compareAndSet(result, null)) {
                        recycleRestorePreviewResult(result);
                    }
                    return result;
                })
                .doOnDispose(() -> {
                    disposed.set(true);
                    if (!delivered.get()) {
                        recycleRestorePreviewResult(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((result, throwable) -> {
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.failed_to_load_image,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // the validation gate (see the api24 note above): a
                    // decode that produced no usable bitmap is treated as a
                    // load failure, never as a blank image
                    if (result.mOriginal == null
                            || result.mOriginal.getWidth() <= 0) {
                        Toast.makeText(activity,
                                R.string.failed_to_load_image,
                                Toast.LENGTH_LONG).show();
                        recycleRestorePreviewResult(result);
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (currentState == null) {
                        return;
                    }
                    if (mPreviewOriginal != null) {
                        mPreviewOriginal.recycle();
                    }
                    mPreviewOriginal = result.mOriginal;
                    result.mOriginal = null;
                    if (mPreviewDeblurred != null) {
                        mPreviewDeblurred.recycle();
                    }
                    mPreviewDeblurred = result.mDeblurred;
                    result.mDeblurred = null;
                    if (mPreviewDeblurred == null) {
                        // the persisted deblur file is gone or undecodable
                        // (e.g. purged), the state drops it so the toggle
                        // hides and a fresh deblur rebuilds it
                        currentState.mDeblurredTempPath = null;
                        currentState.mShowingDeblurred = false;
                    }
                    mEditorState.onNext(currentState);
                }));
    }

    /**
     * Re-decode the persisted previews of a restored session (ownership to caller)
     */
    private RestorePreviewResult restoreTask(EditorState editorState)
            throws IOException {
        Bitmap original = mImageHelper.decodeDownscaledBitmap(
                readSourceUri(editorState), PREVIEW_MAX_DIM);
        Bitmap deblurred = null;
        if (editorState.mDeblurredTempPath != null) {
            try {
                deblurred = mImageHelper.decodeFullBitmap(Uri.fromFile(
                        new File(editorState.mDeblurredTempPath)));
            } catch (Exception | OutOfMemoryError e) {
                // the deblurred preview is optional (a fresh deblur
                // rebuilds it), the original decode must not fail with it
                mLogger.e(TAG, e.getMessage(), e);
            }
        }
        return new RestorePreviewResult(original, deblurred);
    }

    /**
     * Release a restore result nobody delivered (page disposed mid-decode)
     */
    private static void recycleRestorePreviewResult(RestorePreviewResult result) {
        if (result == null) {
            return;
        }
        if (result.mOriginal != null) {
            result.mOriginal.recycle();
            result.mOriginal = null;
        }
        if (result.mDeblurred != null) {
            result.mDeblurred.recycle();
            result.mDeblurred = null;
        }
    }

    /**
     * Flip the before/after toggle (needs a successful deblur)
     */
    private void toggleBeforeAfter() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mDeblurredTempPath == null) {
            return;
        }
        editorState.mShowingDeblurred = !editorState.mShowingDeblurred;
        mEditorState.onNext(editorState);
    }

    /**
     * Deblur behind the busy-guard at the working cap; the result is
     * persisted to a temp file and shown; failures are toasts, retry stays
     * allowed.
     */
    private void deblurPreview() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mPreviewWidth <= 0) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0,
                activity.getString(R.string.photo_restore_deblurring_progress));
        int workingCap = suggestedPhotoRestoreWorkingCap(mAppContext);
        Uri sourceUri = readSourceUri(editorState);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<DeblurPreviewResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("deblurPreview", Single.fromCallable(() -> {
                    DeblurPreviewResult result = deblurTask(sourceUri, workingCap);
                    pendingResult.set(result);
                    if (disposed.get()
                            && pendingResult.compareAndSet(result, null)) {
                        recycleDeblurPreviewResult(result);
                    }
                    return result;
                })
                .doOnDispose(() -> {
                    disposed.set(true);
                    if (!delivered.get()) {
                        recycleDeblurPreviewResult(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((result, throwable) -> {
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // the CvException of a memory-starved forward
                        // arrives here as a plain throwable (:app has no
                        // org.opencv on its compile classpath) — log +
                        // error toast, the retry stays allowed (the cached
                        // net survives)
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.photo_restore_error_deblur_failed,
                                Toast.LENGTH_LONG).show();
                        updateActionControls(mEditorState.getValue());
                        return;
                    }
                    onDeblurred(result);
                }));
    }

    /**
     * Preview deblur on a background thread (decode to cap with exact
     * rescale, then persist)
     */
    private DeblurPreviewResult deblurTask(Uri sourceUri, int workingCap)
            throws IOException {
        Bitmap input = mImageHelper.decodeDownscaledBitmap(sourceUri, workingCap);
        try {
            int longSide = Math.max(input.getWidth(), input.getHeight());
            if (longSide > workingCap) {
                double scale = workingCap / (double) longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(input,
                        Math.max(1, (int) Math.round(input.getWidth() * scale)),
                        Math.max(1, (int) Math.round(input.getHeight() * scale)),
                        true);
                if (scaled != input) {
                    input.recycle();
                    input = scaled;
                }
            }
            Bitmap deblurred = mPhotoRestoreEngine.deblurPreview(input, workingCap);
            File deblurredFile = mImageHelper.createImageTempFile(
                    DEBLUR_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                    deblurred);
            return new DeblurPreviewResult(deblurredFile, deblurred);
        } catch (Exception | OutOfMemoryError e) {
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError)
            // instead of delivering them to the error consumer, wrap it
            // into a runtime exception so it is handled as a deblur failure
            // (the input bitmap is recycled by the finally below)
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            input.recycle();
        }
    }

    /**
     * Release a deblur result nobody delivered (page disposed mid-deblur)
     */
    private static void recycleDeblurPreviewResult(DeblurPreviewResult result) {
        if (result == null) {
            return;
        }
        if (result.mDeblurredBitmap != null) {
            result.mDeblurredBitmap.recycle();
        }
        if (result.mDeblurredFile != null) {
            result.mDeblurredFile.delete();
        }
    }

    /**
     * Apply the deblur result on the main thread (persist path, display)
     */
    private void onDeblurred(DeblurPreviewResult result) {
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            recycleDeblurPreviewResult(result);
            return;
        }
        // registered from here (main thread) on: a dispose can only clear
        // the session list on the same thread, so the file can never be
        // stranded
        registerSessionFile(result.mDeblurredFile);
        editorState.mDeblurredTempPath = result.mDeblurredFile.getAbsolutePath();
        editorState.mShowingDeblurred = true;
        editorState.mHasUnsavedChanges = true;
        if (mPreviewDeblurred != null) {
            mPreviewDeblurred.recycle();
        }
        mPreviewDeblurred = result.mDeblurredBitmap;
        mEditorState.onNext(editorState);
    }

    /**
     * Save to the gallery via the tiled pipeline (confirms if a preview exists)
     */
    private void saveImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mPreviewWidth <= 0) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        if (editorState.mDeblurredTempPath != null) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.photo_restore_title_deblur_full)
                    .setMessage(R.string.photo_restore_msg_deblur_full)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> proceedSaveFull(editorState))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        proceedSaveFull(editorState);
    }

    /**
     * Run the full-resolution tiled deblur and insert into the gallery
     */
    private void proceedSaveFull(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0,
                activity.getString(R.string.photo_restore_full_res_deblur_progress));
        Uri sourceUri = readSourceUri(editorState);
        int tileSize = fullResTileSize();
        int overlap = tileSize / TILE_OVERLAP_DIVISOR;
        mRxDisposer.add("saveImage", Single.fromCallable(() ->
                        saveTask(sourceUri, tileSize, overlap))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((saved, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // the tiled deblur failing (typically the native
                        // dnn "Insufficient memory" CvException) is caught
                        // here as an error toast: the cached net survives,
                        // a retry stays allowed
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.photo_restore_error_deblur_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (saved) {
                        editorState.mHasUnsavedChanges = false;
                        mEditorState.onNext(editorState);
                        showSaveSummary(activity);
                    } else {
                        // the gallery insert itself failed (the deblur
                        // already succeeded), surface it like the throwable
                        // path does — a silent failure is otherwise
                        // invisible to the user
                        String failureMessage = activity.getString(
                                R.string.failed_to_save_,
                                activity.getString(R.string.photo_restore_title));
                        mLogger.e(TAG, failureMessage);
                        Toast.makeText(activity, failureMessage,
                                Toast.LENGTH_LONG).show();
                    }
                }));
    }

    /**
     * Decode the full original, tiled-deblur, insert into the gallery (JPEG)
     */
    @SuppressLint("MissingPermission")
    private boolean saveTask(Uri sourceUri, int tileSize, int overlap) {
        Bitmap fullBitmap = null;
        Bitmap resultBitmap = null;
        try {
            fullBitmap = mImageHelper.decodeFullBitmap(sourceUri);
            resultBitmap = mPhotoRestoreEngine.deblurFull(fullBitmap, tileSize,
                    overlap, this::reportTiledProgress);
            String title = "photo_restore_" + System.currentTimeMillis();
            return mMediaHelper.insertImage(resultBitmap, title, title,
                    Bitmap.CompressFormat.JPEG) != null;
        } catch (Exception | OutOfMemoryError e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            fullBitmap.recycle();
            if (resultBitmap != null) {
                resultBitmap.recycle();
            }
        }
    }

    /**
     * Hand a tiled-pipeline progress update to the main thread (guarded post)
     */
    private void reportTiledProgress(int done, int total) {
        Handler mainHandler = mMainHandler;
        if (mainHandler == null || mIsDisposed) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        if (activity == null) {
            return;
        }
        mainHandler.post(() -> {
            if (!mIsDisposed && mProgressDialog != null
                    && mProgressDialog.isWorking()) {
                mProgressDialog.update(done, total, activity.getString(
                        R.string.photo_restore_full_res_deblur_progress));
            }
        });
    }

    private void showSaveSummary(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_save_result)
                .setMessage(activity.getString(R.string.saved_summary, 1, 1))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> mNavigator.pop())
                .setCancelable(false)
                .show();
    }

    /**
     * Share: like Save, the FULL-resolution original is tiled-deblurred
     */
    private void shareImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mPreviewWidth <= 0) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.preparing_share));
        Uri sourceUri = readSourceUri(editorState);
        int tileSize = fullResTileSize();
        int overlap = tileSize / TILE_OVERLAP_DIVISOR;
        mRxDisposer.add("shareImage", Single.fromCallable(() ->
                        buildShareFile(sourceUri, tileSize, overlap))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((file, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // the tiled deblur failure surfaces as the error
                        // toast (retry allowed, the cached net survives)
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.photo_restore_error_deblur_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // a previous share file is deleted before the new one
                    // replaces it (the page holds only the latest file)
                    if (mLastShareFile != null) {
                        mLastShareFile.delete();
                    }
                    mLastShareFile = file;
                    UiUtils.shareFile(activity, file,
                            activity.getString(R.string.share), "image/jpeg");
                }));
    }

    /**
     * Build the temp file of the deblurred image to share (failures wrapped)
     */
    private File buildShareFile(Uri sourceUri, int tileSize, int overlap) {
        Bitmap fullBitmap = null;
        Bitmap resultBitmap = null;
        try {
            fullBitmap = mImageHelper.decodeFullBitmap(sourceUri);
            resultBitmap = mPhotoRestoreEngine.deblurFull(fullBitmap, tileSize,
                    overlap, this::reportTiledProgress);
            return mImageHelper.createImageTempFile(
                    SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                    resultBitmap, Bitmap.CompressFormat.JPEG);
        } catch (Exception | OutOfMemoryError e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            fullBitmap.recycle();
            if (resultBitmap != null) {
                resultBitmap.recycle();
            }
        }
    }

    private NavBackPressResult handleBack() {
        if (mProgressDialog != null && mProgressDialog.isWorking()) {
            // a deblur/save/share is running, the back press is ignored
            return NavBackPressResult.noPop();
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.photo_restore_msg_discard_changes)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return NavBackPressResult.noPop();
        }
        return NavBackPressResult.pop();
    }
}
