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
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import m.co.rh.id.a_jarwis.app.ui.page.common.ModelDownloadDialog;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.UiUtils;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
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
 * Interactive photo restore (NAFNet deblur + Swin2SR 4x upscale) editor
 * with PRESS-ORDER STACKING: every action composes on the CURRENT working
 * image (deblur after upscale deblurs the upscaled result; upscale twice
 * upscales again — the per-upscale pre-cap keeps it bounded, the
 * effective magnification honestly drops toward 1.0x). Save/Share replay
 * the applied ops in press order at full resolution (STEP-STRICT);
 * nothing applied = a passthrough save/share of the untouched original
 * (no model, no cap); a successful save CONSUMES the edits — the stack
 * clears so a restored session after the save is a clean passthrough
 * editor. The nets are app-lifetime (teardown-poison note on
 * {@link m.co.rh.id.a_jarwis.ml_engine.provider.component.NafNetProcessor})
 * — this page never releases them; every NAFNet forward is safe at any
 * size because MLEngineInstance runs a 512-px synthetic warm-up at
 * processor creation (cold first-forward SEGV law).
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
     * Preview cap of the upscale preview pipeline (the 4x engine result is
     * resized back to the input dimensions, so the preview stays small)
     */
    private static final int UPSCALE_PREVIEW_CAP = 256;
    /**
     * Tile overlap of the full-resolution upscale: Swin2SR 64 px input
     * tile, quarter-tile cross-fade (valid range 0..32)
     */
    private static final int UPSCALE_TILE_OVERLAP = 16;
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
     * File name prefix of the persisted preview upscale result file
     */
    private static final String UPSCALE_FILE_PREFIX = "restore_upscale_";
    /**
     * Applied-op value of {@link EditorState#mAppliedOps}: deblur the
     * working image
     */
    private static final String OP_DEBLUR = "deblur";
    /**
     * Applied-op value of {@link EditorState#mAppliedOps}: 4x upscale the
     * working image
     */
    private static final String OP_UPSCALE = "upscale";

    /**
     * One applied op of the press-order stack: the op type plus its
     * persisted preview temp (every op's temp is KEPT so undo can pop the
     * stack; the age purge bounds them)
     */
    private static class AppliedOp implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String mOp;
        private final String mTempPath;

        private AppliedOp(String op, String tempPath) {
            mOp = op;
            mTempPath = tempPath;
        }
    }

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
         * Applied ops in press order ({@link PhotoRestoreEditorPage#OP_DEBLUR}
         * / {@link PhotoRestoreEditorPage#OP_UPSCALE} types, each with its
         * kept preview temp); the save replays exactly this sequence at
         * full resolution
         */
        private List<AppliedOp> mAppliedOps = new ArrayList<>();
        /**
         * Absolute path of the persisted working image, the pointer to the
         * LAST applied op's temp (null = the source is untouched), the
         * input of every preview op
         */
        private String mWorkingTempPath;
        /**
         * true when the working image is shown (needs mWorkingTempPath)
         */
        private boolean mShowingDeblurred;
        /**
         * Preview dimension of the loaded original, zero means not loaded
         */
        private int mPreviewWidth;
        private int mPreviewHeight;
        private boolean mHasUnsavedChanges;
        /**
         * @deprecated legacy two-slot field, kept ONLY so a process-death
         * snapshot saved before the stacking model deserializes:
         * {@link #readObject} derives the ops list from it
         */
        @Deprecated
        private String mDeblurredTempPath;
        /**
         * @deprecated legacy two-slot field, see {@link #mDeblurredTempPath}
         */
        @Deprecated
        private String mUpscaledTempPath;

        private void readObject(ObjectInputStream in) throws IOException,
                ClassNotFoundException {
            in.defaultReadObject();
            if (mAppliedOps == null) {
                // legacy two-slot snapshot: derive the press-order ops
                // (the pre-stacking chains were always deblur -> upscale)
                mAppliedOps = new ArrayList<>();
                if (mDeblurredTempPath != null) {
                    mAppliedOps.add(new AppliedOp(
                            PhotoRestoreEditorPage.OP_DEBLUR, mDeblurredTempPath));
                }
                if (mUpscaledTempPath != null) {
                    mAppliedOps.add(new AppliedOp(
                            PhotoRestoreEditorPage.OP_UPSCALE, mUpscaledTempPath));
                }
                mWorkingTempPath = mUpscaledTempPath != null
                        ? mUpscaledTempPath : mDeblurredTempPath;
            }
        }
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
     * Result of the preview upscale pipeline (bitmap + persisted temp file)
     */
    private static class UpscalePreviewResult {
        private final File mUpscaledFile;
        private final Bitmap mUpscaledBitmap;

        private UpscalePreviewResult(File upscaledFile, Bitmap upscaledBitmap) {
            mUpscaledFile = upscaledFile;
            mUpscaledBitmap = upscaledBitmap;
        }
    }

    /**
     * Result of the process-death restore preview decode (original + working)
     */
    private static class RestorePreviewResult {
        private Bitmap mOriginal;
        private Bitmap mWorking;

        private RestorePreviewResult(Bitmap original, Bitmap working) {
            mOriginal = original;
            mWorking = working;
        }
    }

    /**
     * Marks the failing op stage of a replayed save/share chain, the
     * error toast picks the stage-matching copy
     */
    private static class PipelineFailureException extends RuntimeException {
        private final boolean mUpscaleStage;

        private PipelineFailureException(boolean upscaleStage, Throwable cause) {
            super(cause.getMessage(), cause);
            mUpscaleStage = upscaleStage;
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
    private transient MaterialButton mButtonUndo;
    private transient View mButtonDeblur;
    private transient View mButtonUpscale;
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
     * The current working preview bitmap (the source with the applied ops
     * composed), the "restored" side of the toggle
     */
    private transient Bitmap mPreviewWorking;
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
        mButtonUndo = rootView.findViewById(R.id.button_undo);
        mButtonUndo.setOnClickListener(this);
        mButtonDeblur = rootView.findViewById(R.id.button_deblur);
        mButtonDeblur.setOnClickListener(this);
        mButtonUpscale = rootView.findViewById(R.id.button_upscale);
        mButtonUpscale.setOnClickListener(this);
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
            // process death restore: re-decode the original and working
            // previews from the persisted private temp files behind the
            // busy-guard
            restorePreviews(editorState);
        }
        updateActionControls(mEditorState.getValue());
        // best effort purge of the temp files left over by previous
        // sessions: only files older than the cutoff are deleted AND the
        // live session's own files (the source copy + every applied op's
        // result of a restored session) are excluded — a restored session
        // keeps the temp files its snapshot points to even when they are
        // older than the cutoff
        Set<String> protectedPaths =
                collectProtectedPaths(mEditorState.getValue());
        mRxDisposer.add("createView_purgeTempFiles", Single.fromCallable(() -> {
                    long maxAgeMillis = TimeUnit.HOURS.toMillis(1);
                    mFileHelper.deleteTempFiles(RESTORE_SOURCE_PREFIX, maxAgeMillis, protectedPaths);
                    mFileHelper.deleteTempFiles(DEBLUR_FILE_PREFIX, maxAgeMillis, protectedPaths);
                    mFileHelper.deleteTempFiles(UPSCALE_FILE_PREFIX, maxAgeMillis, protectedPaths);
                    mFileHelper.deleteTempFiles(SHARE_FILE_PREFIX, maxAgeMillis, protectedPaths);
                    return true;
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .subscribe((aBoolean, throwable) -> {
                    // any failure is ignored
                }));
        return rootView;
    }

    /**
     * Absolute paths of the live session's own temp files (the source
     * copy + every applied op's result + the working image), protected
     * from the aged purge on page creation
     */
    private Set<String> collectProtectedPaths(EditorState editorState) {
        Set<String> protectedPaths = new HashSet<>();
        if (editorState == null) {
            return protectedPaths;
        }
        if (editorState.mSourceTempPath != null) {
            protectedPaths.add(editorState.mSourceTempPath);
        }
        if (editorState.mWorkingTempPath != null) {
            protectedPaths.add(editorState.mWorkingTempPath);
        }
        for (AppliedOp appliedOp : editorState.mAppliedOps) {
            protectedPaths.add(appliedOp.mTempPath);
        }
        return protectedPaths;
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
        if (mPreviewWorking != null) {
            mPreviewWorking.recycle();
            mPreviewWorking = null;
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
        mButtonUndo = null;
        mButtonDeblur = null;
        mButtonUpscale = null;
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
        } else if (id == R.id.button_undo) {
            undoLastOp();
        } else if (id == R.id.button_deblur) {
            deblurPreview();
        } else if (id == R.id.button_upscale) {
            upscalePreview();
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

    /**
     * The input of a preview op: the current working image (the source
     * until the first op lands) — PRESS-ORDER STACKING
     */
    private Uri readWorkingUri(EditorState editorState) {
        if (editorState.mWorkingTempPath != null) {
            return Uri.fromFile(new File(editorState.mWorkingTempPath));
        }
        return readSourceUri(editorState);
    }

    /**
     * Append an applied op to the press-order stack and point the working
     * temp at its persisted result (every op temp is KEPT so undo can pop
     * the stack; the age purge bounds them)
     */
    private void commitOp(EditorState editorState, AppliedOp appliedOp) {
        editorState.mAppliedOps.add(appliedOp);
        editorState.mWorkingTempPath = appliedOp.mTempPath;
    }

    private void onEditorStateChanged(EditorState editorState) {
        updateActionControls(editorState);
        showCurrent(editorState);
    }

    /**
     * Show the state's preview (the working image when toggled, else original)
     */
    private void showCurrent(EditorState editorState) {
        if (mImageView == null) {
            return;
        }
        Bitmap toShow = mPreviewOriginal;
        if (editorState.mShowingDeblurred && mPreviewWorking != null) {
            toShow = mPreviewWorking;
        }
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
        if (mButtonToggleBeforeAfter == null || mButtonUndo == null
                || mButtonDeblur == null || mButtonUpscale == null
                || mButtonSave == null || mButtonShare == null) {
            return;
        }
        boolean working = mProgressDialog != null && mProgressDialog.isWorking();
        boolean loaded = editorState.mPreviewWidth > 0;
        boolean hasWorkingImage = editorState.mWorkingTempPath != null;
        mButtonToggleBeforeAfter.setVisibility(
                hasWorkingImage ? View.VISIBLE : View.GONE);
        mButtonToggleBeforeAfter.setText(
                editorState.mShowingDeblurred ? R.string.photo_restore_show_original
                        : R.string.photo_restore_show_restored);
        mButtonToggleBeforeAfter.setEnabled(!working);
        mButtonUndo.setVisibility(
                hasWorkingImage ? View.VISIBLE : View.GONE);
        mButtonUndo.setText(mAppContext.getString(R.string.photo_restore_undo,
                editorState.mAppliedOps.size()));
        mButtonUndo.setEnabled(!working);
        mButtonDeblur.setEnabled(loaded && !working);
        mButtonUpscale.setEnabled(loaded && !working);
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
                        // the session is unrecoverable (the source is gone
                        // or unreadable): drop the dead state and return
                        // home instead of staring at disabled actions
                        mEditorState.onNext(new EditorState());
                        mNavigator.pop();
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
     * Process-death restore: re-decode the original and working previews
     * (failed decodes are toasts)
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
                        // the session is unrecoverable (its files are gone
                        // or unreadable, e.g. purged before the purge
                        // avoidance existed): drop the dead state and
                        // return home instead of staring at disabled
                        // actions
                        mEditorState.onNext(new EditorState());
                        mNavigator.pop();
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
                        // the session is unrecoverable (see the throwable
                        // path above): drop the dead state and go home
                        mEditorState.onNext(new EditorState());
                        mNavigator.pop();
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
                    // restore the preview dims exactly like startInitialLoad
                    // does: updateActionControls gates every action on them,
                    // a restored session without them stays permanently
                    // disabled
                    currentState.mPreviewWidth = result.mOriginal.getWidth();
                    currentState.mPreviewHeight = result.mOriginal.getHeight();
                    result.mOriginal = null;
                    if (mPreviewWorking != null) {
                        mPreviewWorking.recycle();
                    }
                    mPreviewWorking = result.mWorking;
                    result.mWorking = null;
                    if (mPreviewWorking == null
                            && currentState.mWorkingTempPath != null) {
                        // the persisted working file is gone or undecodable
                        // (e.g. purged): the session drops the applied ops —
                        // the preview ops must always match the replayed
                        // save chain
                        currentState.mAppliedOps.clear();
                        currentState.mWorkingTempPath = null;
                        currentState.mShowingDeblurred = false;
                    }
                    // re-register the restored op temps into THIS process's
                    // session list so the dispose cleanup covers them and
                    // undo can pop them (the list is transient)
                    for (AppliedOp appliedOp : currentState.mAppliedOps) {
                        registerSessionFile(new File(appliedOp.mTempPath));
                    }
                    mEditorState.onNext(currentState);
                }));
    }

    /**
     * Re-decode the persisted previews of a restored session (ownership
     * to caller)
     */
    private RestorePreviewResult restoreTask(EditorState editorState)
            throws IOException {
        Bitmap original = mImageHelper.decodeDownscaledBitmap(
                readSourceUri(editorState), PREVIEW_MAX_DIM);
        Bitmap working = null;
        if (editorState.mWorkingTempPath != null) {
            try {
                working = mImageHelper.decodeFullBitmap(Uri.fromFile(
                        new File(editorState.mWorkingTempPath)));
            } catch (Exception | OutOfMemoryError e) {
                // the working preview is optional (a fresh op rebuilds
                // it), the original decode must not fail with it
                mLogger.e(TAG, e.getMessage(), e);
            }
        }
        return new RestorePreviewResult(original, working);
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
        if (result.mWorking != null) {
            result.mWorking.recycle();
            result.mWorking = null;
        }
    }

    /**
     * Flip the before/after toggle (needs a working image)
     */
    private void toggleBeforeAfter() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mWorkingTempPath == null) {
            return;
        }
        editorState.mShowingDeblurred = !editorState.mShowingDeblurred;
        mEditorState.onNext(editorState);
    }

    /**
     * Undo the LAST applied op: delete its temp file, revert the working
     * pointer to the previous op (or the source when the stack empties —
     * which also returns to the passthrough save semantics), then
     * re-decode and display the reverted working image
     */
    private void undoLastOp() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mAppliedOps.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        AppliedOp popped = editorState.mAppliedOps
                .remove(editorState.mAppliedOps.size() - 1);
        final String revertedPath = editorState.mAppliedOps.isEmpty()
                ? null
                : editorState.mAppliedOps
                        .get(editorState.mAppliedOps.size() - 1).mTempPath;
        editorState.mWorkingTempPath = revertedPath;
        // the popped temp leaves the session list and is deleted here
        unregisterSessionFile(popped.mTempPath);
        new File(popped.mTempPath).delete();
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<Bitmap> pendingResult = new AtomicReference<>();
        mRxDisposer.add("undoLastOp", Single.fromCallable(() -> {
                    Bitmap reverted = revertedPath != null
                            ? mImageHelper.decodeFullBitmap(Uri.fromFile(
                                    new File(revertedPath)))
                            : null;
                    pendingResult.set(reverted);
                    if (disposed.get()
                            && pendingResult.compareAndSet(reverted, null)) {
                        recycleBitmap(reverted);
                    }
                    return reverted;
                })
                .doOnDispose(() -> {
                    disposed.set(true);
                    if (!delivered.get()) {
                        recycleBitmap(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((reverted, throwable) -> {
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        Toast.makeText(activity,
                                R.string.failed_to_load_image,
                                Toast.LENGTH_LONG).show();
                        updateActionControls(mEditorState.getValue());
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (currentState == null) {
                        recycleBitmap(reverted);
                        return;
                    }
                    if (mPreviewWorking != null) {
                        mPreviewWorking.recycle();
                    }
                    mPreviewWorking = reverted;
                    currentState.mShowingDeblurred =
                            !currentState.mAppliedOps.isEmpty();
                    // an emptied stack returns to the passthrough semantics
                    currentState.mHasUnsavedChanges =
                            !currentState.mAppliedOps.isEmpty();
                    mEditorState.onNext(currentState);
                }));
    }

    /**
     * Remove a temp file from the session list (its deletion is handled
     * by the caller), a no-op when absent
     */
    private void unregisterSessionFile(String path) {
        if (mSessionFiles == null) {
            return;
        }
        synchronized (mSessionFiles) {
            Iterator<File> iterator = mSessionFiles.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getAbsolutePath().equals(path)) {
                    iterator.remove();
                    return;
                }
            }
        }
    }

    /**
     * Recycle a bitmap when present
     */
    private static void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }
    }

    /**
     * Deblur behind the busy-guard at the working cap, applied to the
     * CURRENT working image; the result is persisted to a temp file and
     * shown; failures are toasts, retry stays allowed.
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
        Uri inputUri = readWorkingUri(editorState);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<DeblurPreviewResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("deblurPreview", Single.fromCallable(() -> {
                    DeblurPreviewResult result = deblurTask(inputUri, workingCap);
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
     * Apply the deblur result on the main thread (append the op, advance
     * the working pointer, display)
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
        commitOp(editorState, new AppliedOp(OP_DEBLUR,
                result.mDeblurredFile.getAbsolutePath()));
        editorState.mShowingDeblurred = true;
        editorState.mHasUnsavedChanges = true;
        if (mPreviewWorking != null) {
            mPreviewWorking.recycle();
        }
        mPreviewWorking = result.mDeblurredBitmap;
        mEditorState.onNext(editorState);
    }

    /**
     * 4x upscale behind the busy-guard, applied to the CURRENT working
     * image, gated on the Swin2SR model download; the result is persisted
     * to a temp file and shown; failures are toasts, retry stays allowed.
     * The dialog carries a cancel button (the pipeline disposes behind
     * it).
     */
    private void upscalePreview() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mPreviewWidth <= 0) {
            return;
        }
        if (!mPhotoRestoreEngine.isUpscaleAvailable()) {
            pushUpscaleModelDownload();
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0,
                activity.getString(R.string.photo_restore_upscaling_progress),
                this::onUpscaleCancelRequested);
        Uri inputUri = readWorkingUri(editorState);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<UpscalePreviewResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("upscalePreview", Single.fromCallable(() -> {
                    UpscalePreviewResult result = upscaleTask(inputUri,
                            UPSCALE_PREVIEW_CAP);
                    pendingResult.set(result);
                    if (disposed.get()
                            && pendingResult.compareAndSet(result, null)) {
                        recycleUpscalePreviewResult(result);
                    }
                    return result;
                })
                .doOnDispose(() -> {
                    disposed.set(true);
                    if (!delivered.get()) {
                        recycleUpscalePreviewResult(pendingResult.getAndSet(null));
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
                                R.string.photo_restore_error_upscale_failed,
                                Toast.LENGTH_LONG).show();
                        updateActionControls(mEditorState.getValue());
                        return;
                    }
                    onUpscaled(result);
                }));
    }

    /**
     * Prompt the Swin2SR model download, on success the upscale is retried
     * (the HomeWorkflow gate pattern, scoped to this page's upscale model)
     */
    private void pushUpscaleModelDownload() {
        ArrayList<ModelType> missingModels = new ArrayList<>();
        missingModels.add(ModelType.SWIN2SR_UPSCALE);
        mNavigator.push(Routes.MODEL_DOWNLOAD_PAGE,
                new ModelDownloadDialog.ModelList(missingModels),
                (navigator, navRoute, activity, currentView) -> {
                    Serializable serializable = navRoute.getRouteResult();
                    if (serializable instanceof SelectedChoice
                            && ((SelectedChoice) serializable).getSelectedChoice()
                            == SelectedChoice.POSITIVE) {
                        upscalePreview();
                    }
                });
    }

    /**
     * Cancel request of the upscale preview dialog: abort the pipeline
     * (the doOnDispose path recycles the abandoned result), restore the
     * action controls
     */
    private void onUpscaleCancelRequested() {
        // ACCEPTED bounded window: the disposed Swin2SR callable keeps
        // running to completion in the WeightedThreadPool(5) executor, so
        // a pipeline started right after the cancel can transiently run
        // concurrently with it (violating the engine's serialize-calls
        // contract only within this window); the failure mode is the
        // caught CvException -> error toast, so no serialization
        // machinery is added for it
        mRxDisposer.dispose("upscalePreview");
        mProgressDialog.hide();
        updateActionControls(mEditorState.getValue());
    }

    /**
     * Preview upscale on a background thread (decode the working image to
     * the cap with exact rescale, then persist)
     */
    private UpscalePreviewResult upscaleTask(Uri inputUri, int cap)
            throws IOException {
        Bitmap input = mImageHelper.decodeDownscaledBitmap(inputUri, cap);
        try {
            int longSide = Math.max(input.getWidth(), input.getHeight());
            if (longSide > cap) {
                double scale = cap / (double) longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(input,
                        Math.max(1, (int) Math.round(input.getWidth() * scale)),
                        Math.max(1, (int) Math.round(input.getHeight() * scale)),
                        true);
                if (scaled != input) {
                    input.recycle();
                    input = scaled;
                }
            }
            String progressMessage = mAppContext.getString(
                    R.string.photo_restore_upscaling_progress);
            Bitmap upscaled = mPhotoRestoreEngine.upscalePreview(input, cap,
                    (done, total) -> reportTiledProgress(progressMessage,
                            done, total));
            File upscaledFile = mImageHelper.createImageTempFile(
                    UPSCALE_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                    upscaled);
            return new UpscalePreviewResult(upscaledFile, upscaled);
        } catch (Exception | OutOfMemoryError e) {
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError)
            // instead of delivering them to the error consumer, wrap it
            // into a runtime exception so it is handled as an upscale
            // failure (the input bitmap is recycled by the finally below)
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            input.recycle();
        }
    }

    /**
     * Release an upscale result nobody delivered (page disposed mid-upscale)
     */
    private static void recycleUpscalePreviewResult(UpscalePreviewResult result) {
        if (result == null) {
            return;
        }
        if (result.mUpscaledBitmap != null) {
            result.mUpscaledBitmap.recycle();
        }
        if (result.mUpscaledFile != null) {
            result.mUpscaledFile.delete();
        }
    }

    /**
     * Apply the upscale result on the main thread (append the op, advance
     * the working pointer, display)
     */
    private void onUpscaled(UpscalePreviewResult result) {
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            recycleUpscalePreviewResult(result);
            return;
        }
        // registered from here (main thread) on: a dispose can only clear
        // the session list on the same thread, so the file can never be
        // stranded
        registerSessionFile(result.mUpscaledFile);
        commitOp(editorState, new AppliedOp(OP_UPSCALE,
                result.mUpscaledFile.getAbsolutePath()));
        editorState.mShowingDeblurred = true;
        editorState.mHasUnsavedChanges = true;
        if (mPreviewWorking != null) {
            mPreviewWorking.recycle();
        }
        mPreviewWorking = result.mUpscaledBitmap;
        mEditorState.onNext(editorState);
    }

    /**
     * Save to the gallery via the op replay (confirms if a preview result
     * exists)
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
        if (!editorState.mAppliedOps.isEmpty()) {
            String message = buildSaveFullMessage(activity, editorState);
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.photo_restore_title_save_full)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> proceedSaveFull(editorState))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        proceedSaveFull(editorState);
    }

    /**
     * The step-aware save confirmation message: one sentence per applied
     * op type (consecutive repeats deduped, press order preserved), plus
     * the effective-magnification line whenever an upscale op exists —
     * the dims are replayed arithmetically from the ORIGINAL photo's dims
     * (decodeImageDimension of the source — the same base the save replay
     * decodes, never the preview working image whose dims already include
     * the applied ops) through the op list (each upscale: min(cap,
     * longest) x 4; a deblur leaves them unchanged), so a repeated
     * upscale honestly reports its smaller effective gain
     */
    private String buildSaveFullMessage(Activity activity,
                                        EditorState editorState) {
        StringBuilder message = new StringBuilder();
        String lastSentence = null;
        boolean anyUpscale = false;
        for (AppliedOp appliedOp : editorState.mAppliedOps) {
            anyUpscale |= OP_UPSCALE.equals(appliedOp.mOp);
            String sentence = activity.getString(OP_UPSCALE.equals(appliedOp.mOp)
                    ? R.string.photo_restore_msg_save_upscale
                    : R.string.photo_restore_msg_save_deblur);
            if (!sentence.equals(lastSentence)) {
                if (message.length() > 0) {
                    message.append(' ');
                }
                message.append(sentence);
                lastSentence = sentence;
            }
        }
        if (anyUpscale) {
            try {
                // the base is the ORIGINAL photo (the same source the save
                // replay decodes) — the working preview's dims already
                // include the applied ops and would report a false 1.0x
                Uri sourceUri = readSourceUri(editorState);
                int[] dimension = mImageHelper.decodeImageDimension(sourceUri);
                double longest = Math.max(dimension[0], dimension[1]);
                if (longest <= 0) {
                    // an unreadable source (decodeBounds leaves 0x0 without
                    // throwing) must never fall back to a wrong base — the
                    // effective-scale line is omitted entirely
                    return message.toString();
                }
                double sourceLongest = longest;
                double cap = mPhotoRestoreEngine.suggestedUpscaleCap(mAppContext);
                int upscaleInput = (int) cap;
                for (AppliedOp appliedOp : editorState.mAppliedOps) {
                    if (OP_UPSCALE.equals(appliedOp.mOp)) {
                        // each upscale pre-caps the working image, then 4x
                        upscaleInput = (int) Math.min(cap, longest);
                        longest = upscaleInput * 4.0;
                    }
                    // a deblur leaves the dimensions unchanged
                }
                float effective = (float) (longest / sourceLongest);
                message.append("\n\n").append(activity.getString(
                        R.string.photo_restore_msg_effective_scale,
                        upscaleInput, String.format(Locale.US, "%.1f", effective)));
            } catch (Exception e) {
                // the dimension probe failing must not block saving
                mLogger.e(TAG, e.getMessage(), e);
            }
        }
        return message.toString();
    }

    /**
     * Run the STEP-STRICT full-resolution replay (exactly the applied ops
     * in press order) and insert into the gallery
     */
    private void proceedSaveFull(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        List<AppliedOp> appliedOps = new ArrayList<>(editorState.mAppliedOps);
        FullResProgressListener progress = new FullResProgressListener(
                activity.getString(R.string.photo_restore_full_res_deblur_progress),
                activity.getString(R.string.photo_restore_full_res_upscale_progress));
        // the stage the pipeline starts in (nothing applied = the plain
        // gallery save, the generic string the other editors use)
        String initialMessage = appliedOps.isEmpty()
                ? activity.getString(R.string.saving_progress, 1, 1)
                : activity.getString(OP_UPSCALE.equals(appliedOps.get(0).mOp)
                        ? R.string.photo_restore_full_res_upscale_progress
                        : R.string.photo_restore_full_res_deblur_progress);
        mProgressDialog.show(false, 0, initialMessage);
        Uri sourceUri = readSourceUri(editorState);
        mRxDisposer.add("saveImage", Single.fromCallable(() ->
                        saveTask(sourceUri, appliedOps, progress))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((saved, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // the replayed pipeline failing (typically the
                        // native dnn "Insufficient memory" CvException) is
                        // caught here as a stage-matched error toast: the
                        // cached nets survive, a retry stays allowed (a
                        // passthrough save has no model stage, its decode
                        // failure uses the generic load-failure copy)
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        int errorRes = R.string.failed_to_load_image;
                        if (throwable instanceof PipelineFailureException) {
                            errorRes = ((PipelineFailureException) throwable).mUpscaleStage
                                    ? R.string.photo_restore_error_upscale_failed
                                    : R.string.photo_restore_error_deblur_failed;
                        }
                        Toast.makeText(activity, errorRes,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (saved) {
                        // save CONSUMES the edits (step-strict): the stack
                        // clears so a session restored after this point is
                        // a clean passthrough editor — no ambiguous
                        // half-saved middle state
                        editorState.mAppliedOps.clear();
                        editorState.mWorkingTempPath = null;
                        editorState.mShowingDeblurred = false;
                        editorState.mHasUnsavedChanges = false;
                        if (mPreviewWorking != null) {
                            mPreviewWorking.recycle();
                            mPreviewWorking = null;
                        }
                        mEditorState.onNext(editorState);
                        showSaveSummary(activity);
                    } else {
                        // the gallery insert itself failed (the pipeline
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
     * Decode the full source and REPLAY the applied ops in press order at
     * full resolution (STEP-STRICT; empty ops = the passthrough gallery
     * save of the untouched original). Intermediates are recycled as the
     * chain advances; the final result is recycled after the insert.
     */
    @SuppressLint("MissingPermission")
    private boolean saveTask(Uri sourceUri, List<AppliedOp> appliedOps,
                             FullResProgressListener progress) {
        Bitmap working = null;
        try {
            working = mImageHelper.decodeFullBitmap(sourceUri);
            for (AppliedOp appliedOp : appliedOps) {
                boolean upscale = OP_UPSCALE.equals(appliedOp.mOp);
                Bitmap next;
                try {
                    if (upscale) {
                        Bitmap capped = capBitmap(working,
                                mPhotoRestoreEngine.suggestedUpscaleCap(mAppContext));
                        try {
                            next = mPhotoRestoreEngine.upscaleFull(capped,
                                    UPSCALE_TILE_OVERLAP,
                                    progress.stageListener(appliedOp.mOp));
                        } finally {
                            if (capped != working) {
                                capped.recycle();
                            }
                        }
                    } else {
                        int tileSize = fullResTileSize();
                        int overlap = tileSize / TILE_OVERLAP_DIVISOR;
                        next = mPhotoRestoreEngine.deblurFull(working, tileSize,
                                overlap, progress.stageListener(appliedOp.mOp));
                    }
                } catch (Exception | OutOfMemoryError e) {
                    // surface the failing stage to the error consumer (the
                    // toast picks the stage-matching copy); RxJava rethrows
                    // fatal throwables instead of delivering them, the wrap
                    // keeps the failure on the error path
                    throw new PipelineFailureException(upscale, e);
                }
                // the previous chain image is consumed by this stage
                working.recycle();
                working = next;
            }
            String title = "photo_restore_" + System.currentTimeMillis();
            return mMediaHelper.insertImage(working, title, title,
                    Bitmap.CompressFormat.JPEG) != null;
        } catch (Exception | OutOfMemoryError e) {
            if (e instanceof PipelineFailureException) {
                throw (PipelineFailureException) e;
            }
            // the decode failure (passthrough included) — generic wrap
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            if (working != null) {
                working.recycle();
            }
        }
    }

    /**
     * Aspect-preserving pre-cap of a bitmap to the cap (the upscale
     * engine hard-caps its input): returns the SAME bitmap when already
     * within the cap, else a new scaled one (the caller owns both)
     */
    private static Bitmap capBitmap(Bitmap bitmap, int cap) {
        int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longSide > cap) {
            double scale = cap / (double) longSide;
            return Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, (int) Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, (int) Math.round(bitmap.getHeight() * scale)),
                    true);
        }
        return bitmap;
    }

    /**
     * Cumulative per-op listener of the STEP-REPLAY save/share pipelines:
     * every replayed op's tile counts re-base onto the accumulated total
     * ((base+u, base+U)) — the numbers never reset between ops, only the
     * message switches per op type
     */
    private class FullResProgressListener {
        private final String mDeblurMessage;
        private final String mUpscaleMessage;
        private int mAccumulated;
        private int mStageTotal;

        private FullResProgressListener(String deblurMessage,
                                        String upscaleMessage) {
            mDeblurMessage = deblurMessage;
            mUpscaleMessage = upscaleMessage;
        }

        private ProgressListener stageListener(String op) {
            mStageTotal = 0;
            String message = OP_UPSCALE.equals(op)
                    ? mUpscaleMessage : mDeblurMessage;
            return (done, total) -> {
                mStageTotal = total;
                reportTiledProgress(message, mAccumulated + done,
                        mAccumulated + total);
            };
        }

        private void endStage() {
            mAccumulated += mStageTotal;
            mStageTotal = 0;
        }
    }

    /**
     * Hand a tiled-pipeline progress update to the main thread (guarded post)
     */
    private void reportTiledProgress(String message, int done, int total) {
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
                mProgressDialog.update(done, total, message);
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
     * Share: like Save, the STEP-STRICT op replay (exactly the applied
     * ops in press order) runs on the original
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
        List<AppliedOp> appliedOps = new ArrayList<>(editorState.mAppliedOps);
        FullResProgressListener progress = new FullResProgressListener(
                activity.getString(R.string.photo_restore_full_res_deblur_progress),
                activity.getString(R.string.photo_restore_full_res_upscale_progress));
        mProgressDialog.show(false, 0, activity.getString(R.string.preparing_share));
        Uri sourceUri = readSourceUri(editorState);
        mRxDisposer.add("shareImage", Single.fromCallable(() ->
                        buildShareFile(sourceUri, appliedOps, progress))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((file, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // the replayed pipeline failure surfaces as the
                        // error toast (retry allowed, the cached nets
                        // survive; a passthrough share has no model stage,
                        // its decode failure uses the generic load-failure
                        // copy)
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        int errorRes = R.string.failed_to_load_image;
                        if (throwable instanceof PipelineFailureException) {
                            errorRes = ((PipelineFailureException) throwable).mUpscaleStage
                                    ? R.string.photo_restore_error_upscale_failed
                                    : R.string.photo_restore_error_deblur_failed;
                        }
                        Toast.makeText(activity, errorRes,
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
     * Build the temp file of the processed image to share — the
     * STEP-STRICT op replay (empty ops = the untouched source); failures
     * wrapped
     */
    private File buildShareFile(Uri sourceUri, List<AppliedOp> appliedOps,
                                FullResProgressListener progress) {
        Bitmap working = null;
        try {
            working = mImageHelper.decodeFullBitmap(sourceUri);
            for (AppliedOp appliedOp : appliedOps) {
                boolean upscale = OP_UPSCALE.equals(appliedOp.mOp);
                Bitmap next;
                try {
                    if (upscale) {
                        Bitmap capped = capBitmap(working,
                                mPhotoRestoreEngine.suggestedUpscaleCap(mAppContext));
                        try {
                            next = mPhotoRestoreEngine.upscaleFull(capped,
                                    UPSCALE_TILE_OVERLAP,
                                    progress.stageListener(appliedOp.mOp));
                        } finally {
                            if (capped != working) {
                                capped.recycle();
                            }
                        }
                    } else {
                        int tileSize = fullResTileSize();
                        int overlap = tileSize / TILE_OVERLAP_DIVISOR;
                        next = mPhotoRestoreEngine.deblurFull(working, tileSize,
                                overlap, progress.stageListener(appliedOp.mOp));
                    }
                } catch (Exception | OutOfMemoryError e) {
                    // surface the failing stage to the error consumer (see
                    // saveTask)
                    throw new PipelineFailureException(upscale, e);
                }
                // the previous chain image is consumed by this stage
                working.recycle();
                working = next;
            }
            return mImageHelper.createImageTempFile(
                    SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                    working, Bitmap.CompressFormat.JPEG);
        } catch (Exception | OutOfMemoryError e) {
            if (e instanceof PipelineFailureException) {
                throw (PipelineFailureException) e;
            }
            // the decode failure (passthrough included) — generic wrap
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            if (working != null) {
                working.recycle();
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
