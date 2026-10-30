package m.co.rh.id.a_jarwis.app.ui.page.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.slider.Slider;

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
import m.co.rh.id.a_jarwis.app.ui.component.BrushOverlayView;
import m.co.rh.id.a_jarwis.app.ui.component.EditorProgressDialog;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.UiUtils;
import m.co.rh.id.a_jarwis.ml_engine.model.EditorStroke;
import m.co.rh.id.a_jarwis.ml_engine.model.StrokeBatch;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MagicEraserEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MagicEraserMask;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Interactive magic eraser editor.
 * The user paints brush strokes over the object to be removed and the marked
 * object is segmented (EfficientSAM): the inference is seconds-long, so the
 * detect runs debounced (AUTO_DETECT_DELAY_SECONDS) after every stroke
 * commit, undo or erase — a newer commit cancels the pending older timer, so
 * a burst of strokes collapses into one run. The detection is displayed as a
 * white tint overlay at the display-only opacity (see MagicEraserEngine
 * OVERLAY_TINT_ALPHA_SCALE) hiding the committed strokes, the user adjusts
 * the strokes and the detection re-runs as needed. Erase inpaints the masked
 * object away (LaMa) at a downscaled preview resolution, reusing the mask
 * of the last Detect while the strokes are unchanged, and every erase step
 * is committed as a {@link StrokeBatch}: the save/share replays all batches
 * at the full resolution before writing the result. The detect always
 * segments the CURRENT preview content (not a re-decode of the source) and
 * every erase replays the committed batches before applying the new one, so
 * the editor never diverges from the persisted result. The heavy native dnn
 * nets are managed against the 32-bit address space strictly sequentially:
 * SAM is released before every erase, LaMa after every erase and both nets
 * on dispose (OpenCV dnn retains a large workspace inside them). SAM+LaMa
 * co-residency measured a NATIVE SIGABRT on the 32-bit emulator (an uncaught
 * std::bad_alloc inside libc++_shared during the LaMa load/forward,
 * uncatchable from Java — process death), so the nets never share the
 * address space: the erase runs on the freed SAM space and the frequent
 * detect after an erase lazily reloads SAM, where any reload OOM is caught
 * and surfaced (toast + self-heal) instead of killing the process.
 */
public class MagicEraserEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "MagicEraserEditorPage";

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "magiceraser_share_";
    /**
     * File name prefix of the private cache copy of a picked image, created once
     * before the first erase because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "magic_eraser_source_";
    /**
     * Maximum number of committed erase batches, a full history is rejected
     * on erase with a message (the save/share replays the whole history, an
     * unbounded history would dominate the save time)
     */
    private static final int APPLY_HISTORY_CAP = 10;
    /**
     * Maximum number of strokes marked at once, the oldest stroke is dropped
     * beyond it (the strokes only drive the object marking, 50 is far beyond
     * a realistic marking)
     */
    private static final int MAX_CURRENT_STROKES = 50;
    /**
     * Debounce delay of the auto-detect scheduled after a stroke commit:
     * consecutive strokes keep rescheduling the timer so the seconds-long
     * SAM inference runs once after the user stops marking, not per stroke.
     * 1s chosen so the flow feels automatic on real devices while still
     * collapsing a burst of strokes into one inference
     */
    private static final int AUTO_DETECT_DELAY_SECONDS = 1;

    /**
     * Serializable editor state of this page,
     * only holds serializable fields (strings, boxed types),
     * so it survives the navigator snapshot, never hold Uri/Bitmap here
     */
    private static class EditorState implements Serializable {
        private static final long serialVersionUID = 1L;

        private String mSourceUri;
        private ArrayList<StrokeBatch> mApplyHistory;
        private ArrayList<EditorStroke> mCurrentStrokes;
        private int mBrushSize;
        private boolean mHasUnsavedChanges;
    }

    /**
     * Result of the erase pipeline, used while the erase is running
     */
    private static class EraseResult {
        /**
         * Private cache copy of the source image created before the first
         * erase when the source uri is not a file uri, null when the source is
         * already a file uri or the copy failed (the original uri keeps being
         * used)
         */
        private File mSourceFile;
        /**
         * The erased preview bitmap, null when nothing was erased
         * (no object detected or the pipeline failed)
         */
        private Bitmap mResult;
        /**
         * true when the segmentation found no object, the erase is skipped
         * with a note
         */
        private boolean mNoObject;
        /**
         * Snapshot of the stroke list the erase was computed from, the batch
         * committed on the success path is exactly this snapshot (strokes
         * drawn during the running erase stay in the current strokes)
         */
        private ArrayList<EditorStroke> mStrokes;
    }

    /**
     * Result of the detect pipeline, used while the detect pipeline is
     * running: the mask handle is kept on the page for the erase reuse and
     * the white tint overlay bitmap (display-only, at the engine's
     * OVERLAY_TINT_ALPHA_SCALE opacity) is displayed on the mask overlay view
     */
    private static class DetectResult {
        private final MagicEraserMask mMask;
        private final Bitmap mOverlay;
        /**
         * Snapshot of the stroke list the mask was computed from, identity
         * compared against the current strokes on the result delivery (a
         * stroke change during the detect makes the result stale)
         */
        private final ArrayList<EditorStroke> mStrokes;
        /**
         * true when the segmentation found no object, the detect is skipped
         * with a note
         */
        private final boolean mNoObject;

        private DetectResult(MagicEraserMask mask, Bitmap overlay,
                             ArrayList<EditorStroke> strokes) {
            this(mask, overlay, strokes, false);
        }

        private DetectResult(MagicEraserMask mask, Bitmap overlay,
                             ArrayList<EditorStroke> strokes, boolean noObject) {
            mMask = mask;
            mOverlay = overlay;
            mStrokes = strokes;
            mNoObject = noObject;
        }

        private static DetectResult noObject() {
            return new DetectResult(null, null, null, true);
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
    private transient MagicEraserEngine mMagicEraserEngine;
    /**
     * Lazy engine instance handle, used to release the native dnn processors
     * (SAM before every erase, LaMa after every erase, both nets on dispose,
     * see {@link MLEngineInstance#releaseEfficientSamProcessor()}
     * and {@link MLEngineInstance#releaseLamaProcessor()})
     */
    private transient ProviderValue<MLEngineInstance> mMLEngineInstance;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient AppCompatImageView mMaskOverlayView;
    private transient BrushOverlayView mBrushOverlayView;
    private transient Slider mSliderBrushSize;
    private transient AppCompatTextView mTextNoObject;
    private transient View mButtonUndo;
    private transient View mButtonReset;
    private transient View mButtonErase;
    /**
     * Busy-guard progress dialog of the editor pipelines (see
     * {@link EditorProgressDialog}), its isWorking is the gate every
     * pipeline relies on
     */
    private transient EditorProgressDialog mProgressDialog;

    // runtime
    /**
     * The preview bitmap currently shown on mImageView, the erase applies to
     * it and its result becomes the new preview base
     */
    private transient Bitmap mPreviewBase;
    /**
     * The white tint mask bitmap (display-only, at the engine's
     * OVERLAY_TINT_ALPHA_SCALE opacity) currently shown on mMaskOverlayView,
     * replaced on every Detect
     */
    private transient Bitmap mMaskBitmap;
    /**
     * The mask handle of the last Detect, kept for the erase reuse and
     * released when the strokes change (the mask no longer matches them),
     * on a following Detect or on dispose
     */
    private transient MagicEraserMask mDetectedMask;
    /**
     * Snapshot of the stroke list the detected mask was computed from,
     * identity-compared against the current stroke list (committed strokes
     * are never mutated)
     */
    private transient ArrayList<EditorStroke> mDetectedStrokes;
    private transient File mLastShareFile;
    /**
     * Private cache copies of the picked image created by this session,
     * never serialized. Deleted on dispose, a process death leaves them in
     * place so the restored snapshot can still read them (stale leftovers are
     * purged by age in a later session). A synchronized list: the erase task
     * registers its copy from the executor thread while the main thread can
     * add/dispose concurrently
     */
    private transient List<File> mSessionSourceFiles;
    /**
     * Guard flag while the brush slider reflects the state brush size
     * programmatically, so the update does not feed back into the change
     * handler
     */
    private transient boolean mIsUpdatingBrushControls;
    /**
     * Set as the very first step of {@link #dispose(Activity)}: the erase
     * task checks it under the mSessionSourceFiles monitor before registering
     * its source copy, so a copy created while the page is being disposed is
     * deleted by the task itself instead of being stranded in the
     * already-cleared session list
     */
    private transient volatile boolean mIsDisposed;

    public MagicEraserEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mMagicEraserEngine = mSvProvider.get(MagicEraserEngine.class);
        mMLEngineInstance = mSvProvider.lazyGet(MLEngineInstance.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level source copy list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionSourceFiles = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_magic_eraser_editor, container, false);
        // the busy-guard progress dialog component is created before the
        // first pipeline can start (the preview load below shows it)
        mProgressDialog = new EditorProgressDialog(activity, R.string.title_magic_eraser);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        mImageView = rootView.findViewById(R.id.imageView);
        mMaskOverlayView = rootView.findViewById(R.id.mask_overlay);
        mBrushOverlayView = rootView.findViewById(R.id.brush_overlay);
        mBrushOverlayView.setOnStrokeCommittedListener(this::onStrokeCommitted);
        // the overlay maps the touches through the image view matrix, resync
        // it whenever the image view lays out (initial layout, size changes)
        mImageView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) ->
                mImageView.post(this::syncOverlayMatrix));
        mSliderBrushSize = rootView.findViewById(R.id.slider_brush_size);
        mSliderBrushSize.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                // nothing to track, the size is applied on stop tracking so
                // the strokes drawn meanwhile use the last committed size
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                changeBrushSize(Math.round(slider.getValue()));
            }
        });
        mTextNoObject = rootView.findViewById(R.id.text_no_object);
        mButtonUndo = rootView.findViewById(R.id.button_undo);
        mButtonUndo.setOnClickListener(this);
        mButtonReset = rootView.findViewById(R.id.button_reset);
        mButtonReset.setOnClickListener(this);
        mButtonErase = rootView.findViewById(R.id.button_erase);
        mButtonErase.setOnClickListener(this);
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
        EditorState editorState = mEditorState.getValue();
        if (editorState.mApplyHistory.isEmpty()) {
            // no committed erase yet, show the original preview
            loadPreview(editorState);
        } else {
            // process death restore, replay the committed erase batches at
            // the preview resolution behind the busy-guard progress dialog
            restoreEdits(editorState);
        }
        // the controls reflect the restored state directly, the state emission
        // (which normally syncs them) only runs on a state change
        updateBrushControls(editorState);
        updateActionControls(editorState);
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
        // flagged before any cleanup: the erase task registers its source
        // copy under the same monitor this flag is checked with (see
        // registerSessionSourceFile), so nothing created from here on can
        // outlive the session cleanup below
        mIsDisposed = true;
        if (mProgressDialog != null) {
            mProgressDialog.dispose();
            mProgressDialog = null;
        }
        if (mPreviewBase != null) {
            mPreviewBase.recycle();
            mPreviewBase = null;
        }
        if (mMaskBitmap != null) {
            mMaskBitmap.recycle();
            mMaskBitmap = null;
        }
        if (mDetectedMask != null) {
            mDetectedMask.release();
            mDetectedMask = null;
        }
        mDetectedStrokes = null;
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        // release the native dnn nets with this page: SAM is only needed
        // while this editor is open and LaMa is already released after
        // every erase — the OpenCV dnn workspace retained inside the nets
        // (~1 GB worst case) must not stay reserved after the editor closes.
        // The releases run on the background executor: each blocks on its
        // processor monitor while a forward may still be in flight (seconds
        // of main-thread stall otherwise), the monitor stays the
        // correctness guarantee. The engine instance is captured first:
        // dispose nulls the page fields below while the submitted release
        // may run after them
        if (mExecutorService != null && mMLEngineInstance != null) {
            MLEngineInstance engineInstance = mMLEngineInstance.get();
            mExecutorService.submit(() -> {
                engineInstance.releaseEfficientSamProcessor();
                engineInstance.releaseLamaProcessor();
            });
        }
        if (mSessionSourceFiles != null) {
            // best effort deletion of the source copies owned by this session,
            // nothing reads them once the editor page is popped. The list is kept
            // (cleared only) so a straggling emission can never NPE
            synchronized (mSessionSourceFiles) {
                for (File sourceFile : mSessionSourceFiles) {
                    sourceFile.delete();
                }
                mSessionSourceFiles.clear();
            }
        }
        mImageView = null;
        mMaskOverlayView = null;
        mBrushOverlayView = null;
        mSliderBrushSize = null;
        mTextNoObject = null;
        mButtonUndo = null;
        mButtonReset = null;
        mButtonErase = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mMagicEraserEngine = null;
        mMLEngineInstance = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
    }

    /**
     * Release the cached LaMa processor through the engine instance (no-op
     * when the page components are already gone), the engine instance
     * lazily recreates the processor on the next erase
     */
    private void releaseLamaProcessor() {
        if (mMLEngineInstance != null) {
            mMLEngineInstance.get().releaseLamaProcessor();
        }
    }

    /**
     * Release the cached SAM processor through the engine instance (no-op
     * when the page components are already gone), the engine instance
     * lazily recreates the processor on the next detect
     */
    private void releaseSamProcessor() {
        if (mMLEngineInstance != null) {
            mMLEngineInstance.get().releaseEfficientSamProcessor();
        }
    }

    /**
     * Register a created source copy into the session list under the same
     * monitor the dispose cleanup holds, so a copy created while the page is
     * being disposed can never be stranded in the already-cleared list.
     *
     * @return false when the page is already disposing (or its components
     * are gone): the copy was deleted here and the caller must keep using
     * the original uri, nothing else would delete the file
     */
    private boolean registerSessionSourceFile(File sourceFile) {
        if (mSessionSourceFiles == null) {
            sourceFile.delete();
            return false;
        }
        synchronized (mSessionSourceFiles) {
            if (mIsDisposed) {
                sourceFile.delete();
                return false;
            }
            mSessionSourceFiles.add(sourceFile);
            return true;
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_undo) {
            undoLastStroke();
        } else if (id == R.id.button_reset) {
            resetStrokes();
        } else if (id == R.id.button_erase) {
            eraseMarkedObject();
        } else if (id == R.id.button_save) {
            saveImage();
        } else if (id == R.id.button_share) {
            shareImage();
        }
    }

    @Override
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        handleBack();
    }

    private EditorState parseRouteArgs() {
        EditorState editorState = new EditorState();
        editorState.mApplyHistory = new ArrayList<>();
        editorState.mCurrentStrokes = new ArrayList<>();
        editorState.mBrushSize = EditorStroke.BRUSH_SIZE_DEFAULT;
        if (mNavRoute != null && mNavRoute.getRouteArgs() instanceof UriList) {
            ArrayList<String> uris = ((UriList) mNavRoute.getRouteArgs()).getUris();
            if (!uris.isEmpty()) {
                // the magic eraser edits a single image, only the first
                // picked uri is used
                editorState.mSourceUri = uris.get(0);
            }
        }
        return editorState;
    }

    private void onEditorStateChanged(EditorState editorState) {
        mBrushOverlayView.setStrokes(editorState.mCurrentStrokes);
        updateBrushControls(editorState);
        updateActionControls(editorState);
        if (editorState.mCurrentStrokes.isEmpty()) {
            // nothing marked anymore (erased/reset), the detected mask is stale
            releaseDetectedMask();
        }
    }

    /**
     * Decode the downscaled preview of the source image and display it.
     */
    private void loadPreview(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        mRxDisposer.add("loadPreview", Single.fromCallable(() ->
                        mImageHelper.decodeDownscaledBitmap(
                                Uri.parse(editorState.mSourceUri), PREVIEW_MAX_DIM))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((preview, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        mLogger.i(TAG, activity.getString(R.string.failed_to_load_image));
                        return;
                    }
                    if (mPreviewBase != null && mPreviewBase != preview) {
                        mPreviewBase.recycle();
                    }
                    mPreviewBase = preview;
                    showResult(preview);
                }));
    }

    /**
     * Swap the bitmap currently shown on the image view.
     * Runs on the main thread, the bitmap lifetime is never shared with the
     * background threads.
     */
    private void showResult(Bitmap result) {
        mImageView.setImageBitmap(result);
        mImageView.invalidate();
        // the fitCenter matrix is computed during the next layout/draw pass,
        // resync the stroke overlay after it
        mImageView.post(this::syncOverlayMatrix);
    }

    /**
     * Push the current image view matrix composed with the preview bitmap
     * size to the stroke overlay, so the strokes and the touch mapping track
     * the displayed image position. The overlay feeds normalized 0..1 image
     * coordinates: preScale applies its scale to the input first
     * (M' = M * S), so scaling by the bitmap size lifts the normalized
     * coordinates into the bitmap pixel space the fitCenter matrix expects
     * (normalized -> pixels -> view). The inverted matrix maps the touch
     * input back into 0..1.
     */
    private void syncOverlayMatrix() {
        if (mImageView == null || mBrushOverlayView == null || mPreviewBase == null) {
            return;
        }
        Matrix matrix = new Matrix(mImageView.getImageMatrix());
        matrix.preScale(mPreviewBase.getWidth(), mPreviewBase.getHeight());
        mBrushOverlayView.setMatrix(matrix);
    }

    /**
     * A stroke was committed by the overlay (the finger was lifted): enforce
     * the current strokes cap, hide the stale no-object note and schedule the
     * debounced auto-detect (a newer commit cancels the pending older timer,
     * see {@link #scheduleAutoDetect()})
     */
    private void onStrokeCommitted() {
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        while (editorState.mCurrentStrokes.size() > MAX_CURRENT_STROKES) {
            // drop the oldest stroke, the overlay paths are rebuilt from the
            // trimmed list
            editorState.mCurrentStrokes.remove(0);
            mBrushOverlayView.setStrokes(editorState.mCurrentStrokes);
        }
        editorState.mHasUnsavedChanges = true;
        updateActionControls(editorState);
        // the drawn stroke invalidates the detected mask, the auto-detect
        // re-runs debounced once the marking settles
        releaseDetectedMask();
        // a stale "no object" note must not sit over the image while the
        // user paints the next mark
        if (mTextNoObject != null) {
            mTextNoObject.setVisibility(View.GONE);
        }
        scheduleAutoDetect();
    }

    /**
     * Schedule the debounced auto-detect through the Rx disposer: because
     * mRxDisposer.add with the same key ("autoDetect") disposes the previous
     * subscription, every newer stroke commit cancels the older pending
     * timer — that IS the debounce, only the newest marking reaches the
     * seconds-long SAM inference. The detect itself runs through
     * {@link #detectObject()} which keeps its own isWorking busy-guard.
     */
    private void scheduleAutoDetect() {
        if (mRxDisposer == null) {
            // the page is already disposed, nothing to schedule
            return;
        }
        mRxDisposer.add("autoDetect", Single.timer(AUTO_DETECT_DELAY_SECONDS,
                        TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((timerResult, throwable) -> runAutoDetect()));
    }

    /**
     * The debounce timer path of {@link #scheduleAutoDetect()}: skip when a
     * pipeline is already running or nothing is marked, otherwise run the
     * detect pipeline (its own isWorking busy-guard re-checks after the
     * timer elapsed).
     */
    private void runAutoDetect() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mCurrentStrokes.isEmpty()) {
            return;
        }
        detectObject();
    }

    /**
     * Segment the marked object behind the busy-guard progress dialog: the
     * EfficientSAM inference is seconds-long, so it runs only through the
     * debounced auto-detect (see {@link #scheduleAutoDetect()}), never per
     * stroke. The Rx unique key makes a double run dispose the first detect
     * instead of running it twice. The detect segments the CURRENT preview
     * content (a copy taken on the main thread, mPreviewBase is never shared
     * with a background thread — see {@link #showResult(Bitmap)}), not a
     * re-decode of the source that would be stale after an erase.
     */
    private void detectObject() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mCurrentStrokes.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.detecting_progress));
        // snapshot of the committed strokes, the in-progress stroke of the
        // overlay is a different object so the list is stable
        ArrayList<EditorStroke> strokes = new ArrayList<>(editorState.mCurrentStrokes);
        // identical copy of the preview for the background task, taken on
        // the main thread so the original is never touched off-thread
        Bitmap previewCopy = null;
        if (mPreviewBase != null) {
            previewCopy = mPreviewBase.copy(mPreviewBase.getConfig(), false);
        }
        // single-owner handoff: the task takes the copy out of the holder
        // when it starts (and recycles it in its finally), the dispose
        // cleanup takes it when the task never started — a dispose racing
        // the task start can neither double-recycle nor leak the copy. A
        // null holder means there was no preview base (the task falls back
        // to the source decode), an emptied holder means the dispose cleanup
        // claimed the copy first (the task drops the run)
        AtomicReference<Bitmap> previewHolder = previewCopy == null
                ? null : new AtomicReference<>(previewCopy);
        // delivered/pending dispose protection: the result is parked the
        // moment the task produced it, a dispose before the delivery
        // releases it (mask Mat and overlay bitmap) instead of leaking it
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<DetectResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("detectObject", Single.fromCallable(() -> {
                    DetectResult detectResult = detectTask(strokes, previewHolder);
                    pendingResult.set(detectResult);
                    if (disposed.get()
                            && pendingResult.compareAndSet(detectResult, null)) {
                        // the dispose taker ran while the result was still
                        // being produced (its getAndSet found the empty
                        // holder): self-release here instead, the CAS
                        // against the taker's getAndSet keeps the release
                        // exactly-once
                        releaseDetectResult(detectResult);
                    }
                    return detectResult;
                })
                .doOnDispose(() -> {
                    // the page was disposed (or a newer detect replaced this
                    // one) before the result reached the UI: flag the
                    // disposal for the callable's self-release above, drain
                    // the preview copy (a task that never started would
                    // otherwise never take it) and release the produced
                    // result the same way the stale-result path of
                    // onObjectDetected does, nothing else would free the
                    // native mask
                    disposed.set(true);
                    Bitmap claimedCopy = previewHolder == null
                            ? null : previewHolder.getAndSet(null);
                    if (claimedCopy != null) {
                        claimedCopy.recycle();
                    }
                    if (!delivered.get()) {
                        releaseDetectResult(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((detectResult, throwable) -> {
                    // ownership moves to the handlers below (onObjectDetected
                    // stores or releases the result, the error path has none)
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    if (throwable != null) {
                        // a failed detect (typically the native dnn
                        // "Insufficient memory" CvException on a fragmented
                        // 32-bit address space) is otherwise invisible to
                        // the user: surface it, the log-only handling below
                        // stays unchanged
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    onObjectDetected(detectResult);
                }));
    }

    /**
     * Run the detect pipeline on a background thread: take the preview copy
     * handed over by {@link #detectObject()} (falling back to a source decode
     * when there is no preview base yet) and segment the marked object. The
     * mask ownership moves into the returned result (released by the caller
     * when the mask is replaced or on dispose), on any failure path it is
     * released here.
     */
    private DetectResult detectTask(ArrayList<EditorStroke> strokes,
                                    AtomicReference<Bitmap> previewHolder)
            throws IOException {
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return DetectResult.noObject();
        }
        Bitmap preview = previewHolder == null
                ? null : previewHolder.getAndSet(null);
        if (preview == null && previewHolder != null) {
            // the dispose cleanup claimed the copy first (the page was
            // disposed before this task could take it), the run is dropped
            return DetectResult.noObject();
        }
        if (preview == null) {
            // no preview base yet (the initial load is still running), fall
            // back to the source decode the first preview is built from
            preview = mImageHelper.decodeDownscaledBitmap(
                    Uri.parse(editorState.mSourceUri), PREVIEW_MAX_DIM);
        }
        MagicEraserMask mask = null;
        try {
            if (mIsDisposed) {
                // the page was disposed while this detect was queued or
                // starting: abort before the SAM forward re-parses the
                // released net into the app-scoped processor cache nobody
                // will release anymore (the finally below recycles the
                // preview copy; the disposed Rx chain never delivers the
                // failure to the dead page)
                throw new IllegalStateException(TAG
                        + " detect aborted: the page is disposed");
            }
            mask = mMagicEraserEngine.computeObjectMask(preview, strokes);
            if (mask == null || !mMagicEraserEngine.hasMask(mask)) {
                // no object for the current strokes, nothing to keep
                return DetectResult.noObject();
            }
            DetectResult detectResult = new DetectResult(mask,
                    mMagicEraserEngine.toOverlayBitmap(mask), strokes);
            mask = null; // the ownership moved into the result
            return detectResult;
        } finally {
            if (mask != null) {
                mask.release();
            }
            if (preview != null) {
                preview.recycle();
            }
        }
    }

    /**
     * Release a detect result nobody delivered to the UI (the page was
     * disposed or a newer detect replaced this pipeline mid-run): the mask
     * and the overlay are freed the same way the stale-result path of
     * {@link #onObjectDetected(DetectResult)} does. Static on purpose: it
     * only touches the result, never the (possibly disposed) page state.
     */
    private static void releaseDetectResult(DetectResult detectResult) {
        if (detectResult == null) {
            return;
        }
        if (detectResult.mMask != null) {
            detectResult.mMask.release();
        }
        if (detectResult.mOverlay != null) {
            detectResult.mOverlay.recycle();
        }
    }

    /**
     * Apply the detect result on the main thread: drop the previous detection,
     * keep the new mask for the erase reuse and display its overlay. A result
     * whose stroke snapshot no longer matches the current strokes (the user
     * marked meanwhile) is stale and discarded.
     */
    private void onObjectDetected(DetectResult detectResult) {
        if (detectResult.mNoObject) {
            // the segmentation found no object for the current strokes
            releaseDetectedMask();
            if (mTextNoObject != null) {
                mTextNoObject.setVisibility(View.VISIBLE);
            }
            mLogger.i(TAG, mNavigator.getActivity()
                    .getString(R.string.no_object_detected));
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null
                || !sameStrokes(editorState.mCurrentStrokes, detectResult.mStrokes)) {
            // the strokes changed while the detect was running, the result is
            // stale (the auto-detect re-runs with the current strokes, so the
            // newest marking still gets detected)
            scheduleAutoDetect();
            detectResult.mMask.release();
            detectResult.mOverlay.recycle();
            return;
        }
        releaseDetectedMask();
        mDetectedMask = detectResult.mMask;
        mDetectedStrokes = detectResult.mStrokes;
        setMaskBitmap(detectResult.mOverlay);
        if (mTextNoObject != null) {
            mTextNoObject.setVisibility(View.GONE);
        }
    }

    /**
     * Drop the detected mask: a stroke change made it stale (commit, undo,
     * reset) or the strokes are gone (erase). The mask overlay display is
     * cleared with it, the debounced auto-detect re-runs the segmentation.
     */
    private void releaseDetectedMask() {
        if (mDetectedMask != null) {
            mDetectedMask.release();
            mDetectedMask = null;
        }
        mDetectedStrokes = null;
        setMaskBitmap(null);
        if (mTextNoObject != null) {
            mTextNoObject.setVisibility(View.GONE);
        }
    }

    /**
     * Hand the currently detected mask to the erase pipeline when the strokes
     * are unchanged since the Detect (identity compare of the stroke list,
     * committed strokes are never mutated), so Erase does not re-run the
     * segmentation the user already triggered. The caller takes ownership:
     * the erase task releases the mask after use.
     */
    private MagicEraserMask takeReusableMask(EditorState editorState) {
        if (mDetectedMask == null
                || !sameStrokes(editorState.mCurrentStrokes, mDetectedStrokes)) {
            return null;
        }
        MagicEraserMask mask = mDetectedMask;
        mDetectedMask = null;
        mDetectedStrokes = null;
        return mask;
    }

    /**
     * Identity compare of two stroke lists: committed strokes are never
     * mutated and the list only grows/shrinks at the edges, so equal sizes
     * with element-wise identical references mean the same marking
     */
    private static boolean sameStrokes(List<EditorStroke> a, List<EditorStroke> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Swap the mask bitmap shown on the mask overlay view, the previous
     * bitmap is recycled
     */
    private void setMaskBitmap(Bitmap maskBitmap) {
        if (mMaskBitmap != null && mMaskBitmap != maskBitmap) {
            mMaskBitmap.recycle();
        }
        mMaskBitmap = maskBitmap;
        if (mMaskOverlayView != null) {
            mMaskOverlayView.setImageBitmap(maskBitmap);
            mMaskOverlayView.setVisibility(
                    maskBitmap == null ? View.GONE : View.VISIBLE);
            mMaskOverlayView.invalidate();
        }
        // the committed strokes hide while the mask tint is displayed
        // (mBrushOverlayView may be null after dispose)
        if (mBrushOverlayView != null) {
            mBrushOverlayView.setMaskDisplayed(maskBitmap != null);
        }
    }

    /**
     * Erase the marked object behind the busy-guard progress dialog. The Rx
     * unique key makes a double tap dispose the first erase instead of
     * running it twice.
     */
    private void eraseMarkedObject() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mCurrentStrokes.isEmpty()) {
            return;
        }
        if (editorState.mApplyHistory.size() >= APPLY_HISTORY_CAP) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_magic_eraser)
                    .setMessage(R.string.apply_history_full)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.removing_progress));
        ArrayList<EditorStroke> strokes = new ArrayList<>(editorState.mCurrentStrokes);
        // snapshot of the committed batches, replayed by the task so this
        // erase operates on the image the user currently sees (all previous
        // erases already applied). The list is only mutated on the main
        // thread, the task works on its own copy
        ArrayList<StrokeBatch> applyHistory = new ArrayList<>(editorState.mApplyHistory);
        // the mask of the last Detect is reused when the strokes are unchanged
        // since it (halves the erase latency), otherwise the erase task
        // computes it fresh; either way the task releases the mask when done
        MagicEraserMask detectedMask = takeReusableMask(editorState);
        // SAM is released BEFORE the erase launches, on the main thread (no
        // SAM forward can be in flight here — every pipeline runs behind the
        // isWorking busy-guard, so the release cannot stall on the processor
        // monitor). Measured evidence: with SAM resident (~591MB retained
        // workspace) the first erase died on the 32-bit emulator with a
        // NATIVE SIGABRT inside libc++_shared — an uncaught std::bad_alloc
        // from dnn's raw operator new during the LaMa load/forward,
        // uncatchable from Java (process death, no self-heal possible).
        // Releasing SAM before every erase keeps the nets strictly
        // sequential — the erase runs on the freed address space
        // (live-verified end-to-end); the post-erase detect lazily reloads
        // SAM (~seconds re-parse, accepted) and any reload OOM is a caught
        // CvException → error toast + self-heal, strictly better than the
        // fatal abort.
        releaseSamProcessor();
        // delivered/pending dispose protection: the result is parked the
        // moment the task produced it, a dispose before the delivery recycles
        // the result bitmap instead of leaking it
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicReference<EraseResult> pendingResult = new AtomicReference<>();
        mRxDisposer.add("eraseObject", Single.fromCallable(() -> {
                    EraseResult eraseResult = eraseTask(editorState.mSourceUri,
                            applyHistory, strokes, detectedMask);
                    pendingResult.set(eraseResult);
                    if (disposed.get()
                            && pendingResult.compareAndSet(eraseResult, null)) {
                        // the dispose taker ran while the result was still
                        // being produced (its getAndSet found the empty
                        // holder): self-release here instead, the CAS
                        // against the taker's getAndSet keeps the release
                        // exactly-once
                        releaseEraseResult(eraseResult);
                    }
                    return eraseResult;
                })
                .doOnDispose(() -> {
                    // the page was disposed before the result reached the UI:
                    // flag the disposal for the callable's self-release above
                    // and recycle the produced bitmap (the source copy file
                    // is handled by the task itself, see
                    // registerSessionSourceFile)
                    disposed.set(true);
                    if (!delivered.get()) {
                        releaseEraseResult(pendingResult.getAndSet(null));
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((eraseResult, throwable) -> {
                    delivered.set(true);
                    pendingResult.set(null);
                    mProgressDialog.hide();
                    // the LaMa native net is only needed during an erase:
                    // release it on EVERY erase end (success AND failure),
                    // the next erase lazily reloads it — the workspace OpenCV
                    // retains inside the net must not stay reserved while the
                    // editor idles (the following auto-detect only needs SAM)
                    releaseLamaProcessor();
                    if (throwable != null) {
                        // error path: the reusable mask was already consumed
                        // (takeReusableMask), so the stale detected tint would
                        // stay displayed with the strokes hidden; drop it and
                        // re-detect (strokes are non-empty here — erase
                        // rejects empty markings before starting — and were
                        // never mutated by the failed erase) to leave the
                        // user in a coherent state
                        // the failed erase (typically an inference
                        // OutOfMemoryError/CvException) is otherwise
                        // invisible to the user: surface it, the self-heal
                        // below stays unchanged
                        Toast.makeText(activity,
                                R.string.error_insufficient_memory,
                                Toast.LENGTH_LONG).show();
                        releaseDetectedMask();
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        scheduleAutoDetect();
                        return;
                    }
                    onObjectErased(editorState, eraseResult);
                }));
    }

    /**
     * Run the erase pipeline on a background thread: copy the source to a
     * private file when needed, decode the downscaled preview of the source
     * and hand it to the engine erase pipeline (the engine replays the
     * committed apply history, reuses the detected mask or segments the
     * marked object fresh, and inpaints it away at the preview resolution).
     * The engine takes ownership of the handed-over reusable mask and of the
     * decoded preview (releases/recycles them when done), the returned
     * result bitmap is owned by the erase result.
     *
     * @param sourceUri    the persisted source uri (the pristine original or
     *                     its private copy)
     * @param applyHistory snapshot of the committed erase batches, replayed
     *                     on the decoded preview before this erase
     * @param strokes      the strokes of this erase, the batch committed on
     *                     success is exactly this list
     * @param detectedMask the reusable mask of the last detect or null
     */
    private EraseResult eraseTask(String sourceUri, List<StrokeBatch> applyHistory,
                                  ArrayList<EditorStroke> strokes,
                                  MagicEraserMask detectedMask)
            throws IOException {
        EraseResult eraseResult = new EraseResult();
        // the snapshot rides on the result: the committed batch is exactly
        // the strokes this task was computed from
        eraseResult.mStrokes = strokes;
        Uri uri = Uri.parse(sourceUri);
        // content uri grants are ephemeral and die with the process, copy the
        // image to a private cache file once before the first erase so the
        // editor still works after a process restart (the snapshot persists
        // the file uri, see onObjectErased)
        if (!"file".equals(uri.getScheme())) {
            eraseResult.mSourceFile = mFileHelper.copySourceToTempFile(
                    EDITOR_SOURCE_PREFIX + 0 + ".jpg", uri);
            if (eraseResult.mSourceFile != null) {
                // registered immediately (not only on the success path):
                // the Rx error path never delivers the result, so a task
                // failure would otherwise leak the copy from the
                // dispose-time cleanup. The registration is refused (and
                // the copy deleted) when the page is being disposed, so
                // the copy can never be stranded in the cleared list
                if (registerSessionSourceFile(eraseResult.mSourceFile)) {
                    uri = Uri.fromFile(eraseResult.mSourceFile);
                } else {
                    eraseResult.mSourceFile = null;
                }
            }
        }
        Bitmap preview;
        try {
            preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        } catch (Exception | OutOfMemoryError e) {
            // the engine never took the handed-over mask (the decode failed
            // before the eraseObject call), release it here — from the
            // eraseObject call on, the engine releases it exactly once
            if (detectedMask != null) {
                detectedMask.release();
            }
            throw e;
        }
        MagicEraserEngine.EraseObjectResult engineResult =
                mMagicEraserEngine.eraseObject(preview, applyHistory, strokes,
                        detectedMask, () -> mIsDisposed);
        if (engineResult.isNoObject()) {
            eraseResult.mNoObject = true;
            return eraseResult;
        }
        eraseResult.mResult = engineResult.getResult();
        return eraseResult;
    }

    /**
     * Release an erase result nobody delivered to the UI (the page was
     * disposed while the erase was running): the result bitmap is recycled
     * the same way a replaced preview base is. Static on purpose: it only
     * touches the result, never the (possibly disposed) page state.
     */
    private static void releaseEraseResult(EraseResult eraseResult) {
        if (eraseResult != null && eraseResult.mResult != null) {
            eraseResult.mResult.recycle();
        }
    }

    /**
     * Apply the erase result on the main thread: replace the source uri with
     * the private copy uri, commit the erase batch into the apply history,
     * clear the current strokes and display the erased preview. On both end
     * states the remaining strokes (kept on the skipped erase, or painted
     * during the running erase) are re-detected automatically through the
     * debounced {@link #scheduleAutoDetect()}.
     */
    private void onObjectErased(EditorState editorState, EraseResult eraseResult) {
        if (eraseResult.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri before
            // the state emission, so the snapshot persists the file uri and
            // every later step (preview rebuild, save, share, restore) reads
            // the copy (the copy is already registered in mSessionSourceFiles
            // by the erase task, immediately after its creation)
            editorState.mSourceUri = Uri.fromFile(eraseResult.mSourceFile).toString();
        }
        if (eraseResult.mNoObject) {
            // the erase is skipped, the state keeps the strokes so the user
            // can refine the marking
            mLogger.i(TAG, mNavigator.getActivity()
                    .getString(R.string.no_object_detected));
            mTextNoObject.setVisibility(View.VISIBLE);
            if (eraseResult.mSourceFile != null) {
                mEditorState.onNext(editorState);
            }
            // the kept strokes are still marked, refresh their detection
            // automatically (e.g. when the erase ran without a prior detect)
            if (!editorState.mCurrentStrokes.isEmpty()) {
                scheduleAutoDetect();
            }
            return;
        }
        mTextNoObject.setVisibility(View.GONE);
        // the committed batch is exactly the erase snapshot: strokes drawn
        // during the running erase stay in the current strokes (the user
        // keeps their ongoing marking and sees it after the state emission)
        editorState.mApplyHistory.add(new StrokeBatch(eraseResult.mStrokes));
        editorState.mCurrentStrokes.removeAll(eraseResult.mStrokes);
        editorState.mHasUnsavedChanges = true;
        // the erased preview becomes the new preview base, the replaced
        // bitmap is released
        if (eraseResult.mResult != null) {
            if (mPreviewBase != null && mPreviewBase != eraseResult.mResult) {
                mPreviewBase.recycle();
            }
            mPreviewBase = eraseResult.mResult;
            showResult(eraseResult.mResult);
        }
        setMaskBitmap(null);
        mEditorState.onNext(editorState);
        // strokes painted during the running erase remain marked, refresh
        // their detection automatically for the next erase
        if (!editorState.mCurrentStrokes.isEmpty()) {
            scheduleAutoDetect();
        }
    }

    /**
     * Remove the last marked stroke and re-trigger the mask refresh: the
     * detection re-runs automatically with the remaining strokes (a no-op
     * when nothing remains, see {@link #runAutoDetect()})
     */
    private void undoLastStroke() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mCurrentStrokes.isEmpty()) {
            return;
        }
        editorState.mCurrentStrokes.remove(editorState.mCurrentStrokes.size() - 1);
        mEditorState.onNext(editorState);
        releaseDetectedMask();
        scheduleAutoDetect();
    }

    /**
     * Clear all marked strokes (the committed batches are final), the state
     * emission hides the mask preview and updates the action buttons
     */
    private void resetStrokes() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mCurrentStrokes.isEmpty()) {
            return;
        }
        editorState.mCurrentStrokes.clear();
        mEditorState.onNext(editorState);
        releaseDetectedMask();
    }

    /**
     * Apply the given brush size to the state so the next strokes use it
     */
    private void changeBrushSize(int brushSize) {
        if (mProgressDialog.isWorking() || mIsUpdatingBrushControls) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null || editorState.mBrushSize == brushSize) {
            return;
        }
        editorState.mBrushSize = brushSize;
        mEditorState.onNext(editorState);
    }

    /**
     * Reflect the state brush size in the slider, the programmatic updates
     * must not feed back into the change handlers
     */
    private void updateBrushControls(EditorState editorState) {
        if (mSliderBrushSize == null) {
            return;
        }
        int brushSize = editorState.mBrushSize;
        if (brushSize < EditorStroke.BRUSH_SIZE_MIN
                || brushSize > EditorStroke.BRUSH_SIZE_MAX) {
            brushSize = EditorStroke.BRUSH_SIZE_DEFAULT;
        }
        mIsUpdatingBrushControls = true;
        try {
            mSliderBrushSize.setValue(brushSize);
            mBrushOverlayView.setBrushSize(brushSize);
        } finally {
            mIsUpdatingBrushControls = false;
        }
    }

    /**
     * Enable/disable the stroke actions: undo/reset work on the current
     * strokes, erase additionally needs a free apply history slot
     */
    private void updateActionControls(EditorState editorState) {
        if (mButtonUndo == null || mButtonReset == null || mButtonErase == null) {
            return;
        }
        boolean hasStrokes = !editorState.mCurrentStrokes.isEmpty();
        mButtonUndo.setEnabled(hasStrokes && !mProgressDialog.isWorking());
        mButtonReset.setEnabled(hasStrokes && !mProgressDialog.isWorking());
        mButtonErase.setEnabled(hasStrokes
                && editorState.mApplyHistory.size() < APPLY_HISTORY_CAP
                && !mProgressDialog.isWorking());
    }

    /**
     * Restore the edited preview after a process death: replay every
     * committed batch at the preview resolution behind the busy-guard
     * progress dialog
     */
    private void restoreEdits(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.restoring_progress));
        mRxDisposer.add("restoreEdits", Single.fromCallable(() -> {
                    Bitmap preview = mImageHelper.decodeDownscaledBitmap(
                            Uri.parse(editorState.mSourceUri), PREVIEW_MAX_DIM);
                    return mMagicEraserEngine.applyBatches(preview,
                            editorState.mApplyHistory, () -> mIsDisposed);
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((restored, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        mLogger.i(TAG, activity.getString(R.string.failed_to_load_image));
                        return;
                    }
                    if (mPreviewBase != null && mPreviewBase != restored) {
                        mPreviewBase.recycle();
                    }
                    mPreviewBase = restored;
                    showResult(restored);
                }));
    }

    private void handleBack() {
        if (mProgressDialog.isWorking()) {
            // a restore/erase/save/share is running, the back press is ignored
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_magic_eraser_changes)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            mNavigator.pop();
        }
    }

    /**
     * Save the edited image to the gallery: the committed batches are replayed
     * at the full resolution (never serialized, always rebuilt) before the
     * result is inserted.
     */
    private void saveImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.saving_progress, 1, 1));
        mRxDisposer.add("saveImage", Single.fromCallable(() ->
                        saveTask(editorState.mSourceUri, editorState.mApplyHistory))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((saved, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    if (saved) {
                        editorState.mHasUnsavedChanges = false;
                        mEditorState.onNext(editorState);
                        showSaveSummary(activity);
                    } else {
                        mLogger.e(TAG, activity.getString(R.string.failed_to_save_,
                                activity.getString(R.string.title_magic_eraser)));
                    }
                }));
    }

    /**
     * Decode the source at the full resolution, replay the committed batches
     * and insert the result into the gallery (JPEG). Runs on a background
     * thread.
     */
    @SuppressLint("MissingPermission")
    private boolean saveTask(String sourceUri, List<StrokeBatch> batches) {
        try {
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(Uri.parse(sourceUri));
            Bitmap resultBitmap = null;
            try {
                resultBitmap = mMagicEraserEngine.applyBatches(fullBitmap,
                        batches, () -> mIsDisposed);
                String title = "magic_eraser_" + System.currentTimeMillis();
                return mMediaHelper.insertImage(resultBitmap, title, title,
                        Bitmap.CompressFormat.JPEG) != null;
            } finally {
                if (resultBitmap != null) {
                    resultBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            return false;
        }
    }

    private void showSaveSummary(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_save_result)
                .setMessage(activity.getString(R.string.saved_summary, 1, 1))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                .setCancelable(false)
                .show();
    }

    /**
     * Share the edited image: the committed batches are replayed at the full
     * resolution and the result is written to a temp file (JPEG) to be shared.
     */
    private void shareImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        mProgressDialog.show(false, 0, activity.getString(R.string.preparing_share));
        mRxDisposer.add("shareImage", Single.fromCallable(() ->
                        buildShareFile(editorState.mSourceUri, editorState.mApplyHistory))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((file, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        mLogger.i(TAG, activity.getString(R.string.share_failed));
                    } else {
                        // a previous share file is deleted before the new one
                        // replaces it (the page holds only the latest file)
                        if (mLastShareFile != null) {
                            mLastShareFile.delete();
                        }
                        mLastShareFile = file;
                        UiUtils.shareFile(activity, file,
                                activity.getString(R.string.share), "image/jpeg");
                    }
                }));
    }

    /**
     * Build the temp file of the edited image to be shared. Runs on a
     * background thread.
     */
    private File buildShareFile(String sourceUri, List<StrokeBatch> batches) {
        try {
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(Uri.parse(sourceUri));
            Bitmap resultBitmap = null;
            try {
                resultBitmap = mMagicEraserEngine.applyBatches(fullBitmap,
                        batches, () -> mIsDisposed);
                return mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                        resultBitmap, Bitmap.CompressFormat.JPEG);
            } finally {
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
