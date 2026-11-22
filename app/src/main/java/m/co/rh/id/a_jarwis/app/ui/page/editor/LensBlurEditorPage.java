package m.co.rh.id.a_jarwis.app.ui.page.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
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
import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.DepthEngine;
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
 * Interactive lens blur editor (a dedicated whole-image workflow, opened
 * from its own home tile — NOT a mode of the portrait editor): applies the
 * depth-aware bokeh of {@link DepthEngine} to every picked image. Pure
 * monocular depth, no person segmentation is involved at all — the whole
 * image is blurred in graduated layers and the layers are blended per pixel
 * by the estimated closeness map (far = strongest blur, near = sharp), so
 * any photo works: landscapes, food, product shots.
 * <p>
 * Per image the user only tunes a blur strength slider (the BlurConfig 1..5
 * range); the depth map is estimated once per image in the preparation pass
 * and engine-cached by the image index, so strength toggles re-blend without
 * re-running the inference. The preview renders at a downscaled preview
 * resolution, the save/share re-reads the cached depth (just-in-time
 * full resolution estimation when the cache is empty) and always exports
 * JPEG. The model download gate lives in {@code HomeWorkflow} before this
 * page is pushed, so the page can assume the depth model is present.
 */
public class LensBlurEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "LensBlurEditorPage";

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "lensblur_share_";
    /**
     * File name prefix of the private cache copy of a picked image, created once
     * per image at preparation time because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "lens_blur_editor_source_";

    /**
     * Serializable editor state of this page,
     * only holds serializable fields (strings, boxed types),
     * so it survives the navigator snapshot, never hold Uri/Bitmap here
     */
    private static class EditorState implements Serializable {
        private static final long serialVersionUID = 1L;

        private ArrayList<String> mUris;
        private int mCurrentIndex;
        private ArrayList<ImageEditState> mImageEditStates;
        private boolean mHasUnsavedChanges;
    }

    /**
     * Edit state of a single image in the batch
     */
    private static class ImageEditState implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * true when the preparation pass ran for the image (the depth map is
         * engine-cached or the preparation failed and the display/save
         * pipelines retry the estimation just in time), null means the image
         * has not been prepared yet
         */
        private Boolean mPrepared;
        /**
         * Blur strength (BlurConfig range)
         */
        private int mStrength;

        private Boolean getPrepared() {
            return mPrepared;
        }

        private void setPrepared(Boolean prepared) {
            mPrepared = prepared;
        }

        private int getStrength() {
            return mStrength;
        }

        private void setStrength(int strength) {
            mStrength = strength;
        }
    }

    /**
     * Display result of the currently displayed image, never serialized
     * (bitmaps must not be in the navigator snapshot)
     */
    private static class ResultCache {
        private final int mImageIndex;
        private final int mStrength;
        private final Bitmap mResult;

        private ResultCache(int imageIndex, int strength, Bitmap result) {
            mImageIndex = imageIndex;
            mStrength = strength;
            mResult = result;
        }
    }

    /**
     * Preparation state of a single image, used while the preparation pipeline is running
     */
    private static class PreparedImage {
        private final int mIndex;
        /**
         * Private cache copy of the source image created during preparation when
         * the source uri is not a file uri, null when the source is already a
         * file uri or the copy failed (the original uri keeps being used)
         */
        private File mSourceFile;

        private PreparedImage(int index) {
            mIndex = index;
        }
    }

    /**
     * Save result of a single image, used while the save pipeline is running
     */
    private static class SaveResult {
        private static final int STATUS_SAVED = 1;
        private static final int STATUS_FAILED = 2;

        private final int mIndex;
        private int mStatus;

        private SaveResult(int index) {
            mIndex = index;
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
    private transient DepthEngine mDepthEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient View mContainerPager;
    private transient AppCompatTextView mTextImagePager;
    private transient AppCompatImageButton mButtonPrevImage;
    private transient AppCompatImageButton mButtonNextImage;
    private transient Slider mSliderBlurStrength;
    /**
     * Busy-guard progress dialog of the editor pipelines (see
     * {@link EditorProgressDialog}), its isWorking is the gate every
     * pipeline relies on
     */
    private transient EditorProgressDialog mProgressDialog;

    // runtime
    private transient ResultCache mResultCache;
    /**
     * The composited bitmap currently shown on mImageView
     */
    private transient Bitmap mDisplayBitmap;
    private transient boolean mIsPreparing;
    private transient int mPreparationProgressCount;
    private transient ArrayList<SaveResult> mSaveResults;
    private transient File mLastShareFile;
    /**
     * Private cache copies of the picked images created by this session,
     * never serialized. Deleted on dispose, a process death leaves them in
     * place so the restored snapshot can still read them (stale leftovers are
     * purged by age in a later session)
     */
    private transient ArrayList<File> mSessionSourceFiles;

    public LensBlurEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mDepthEngine = mSvProvider.get(DepthEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level source copy list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_lens_blur_editor, container, false);
        // the busy-guard progress dialog component is created before the
        // first pipeline can start (the preparation below shows it)
        mProgressDialog = new EditorProgressDialog(activity, R.string.title_lens_blur);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            NavBackPressResult result = handleBack();
            if (result != null && result.shouldPop()) {
                mNavigator.pop();
            }
        });
        mImageView = rootView.findViewById(R.id.imageView);
        mContainerPager = rootView.findViewById(R.id.container_pager);
        mTextImagePager = rootView.findViewById(R.id.text_image_pager);
        mButtonPrevImage = rootView.findViewById(R.id.button_prev_image);
        mButtonPrevImage.setOnClickListener(this);
        mButtonNextImage = rootView.findViewById(R.id.button_next_image);
        mButtonNextImage.setOnClickListener(this);
        mSliderBlurStrength = rootView.findViewById(R.id.slider_blur_strength);
        mSliderBlurStrength.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            /**
             * Image index when the tracking started, the change is skipped when
             * the current image changed meanwhile (e.g. a second finger on the pager)
             */
            private int mTrackedImageIndex;

            @Override
            public void onStartTrackingTouch(Slider slider) {
                EditorState editorState = mEditorState.getValue();
                mTrackedImageIndex = editorState == null ? -1 : editorState.mCurrentIndex;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                // applied on stop tracking so the blurred image is not rebuilt
                // on every tick
                EditorState editorState = mEditorState.getValue();
                if (editorState == null || editorState.mCurrentIndex != mTrackedImageIndex) {
                    // the user switched to another image meanwhile, skip the apply
                    return;
                }
                changeBlurStrength(Math.round(slider.getValue()));
            }
        });
        View saveButton = rootView.findViewById(R.id.button_save);
        saveButton.setOnClickListener(this);
        View shareButton = rootView.findViewById(R.id.button_share);
        shareButton.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        if (mEditorState.getValue() == null) {
            // first open, parse the picked image uris and prepare each image
            mEditorState.onNext(parseRouteArgs());
        }
        // prepare the images that have not been prepared yet (prepared == null),
        // on restore (e.g. process death in the middle of the preparation) only
        // the pending images are re-prepared, the prepared ones keep their
        // persisted states (the engine depth cache is empty then, the display
        // pipeline re-estimates just in time)
        startPreparation(mEditorState.getValue());
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
        if (mDepthEngine != null) {
            // the session depth maps are released so the same keys of a later
            // session never read the maps of this session's images
            mDepthEngine.releaseAllDepths();
            // the depth processor's native dnn workspace (~480-550 MB) must
            // not outlive the lens blur session (the mandatory
            // release-on-dispose + nudge pattern), the next session lazily
            // re-parses the model file
            mDepthEngine.releaseProcessor();
        }
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        if (mSessionSourceFiles != null) {
            // best effort deletion of the source copies owned by this session,
            // nothing reads them once the editor page is popped. The list is kept
            // (cleared only) so a straggling preparation emission can never NPE
            for (File sourceFile : mSessionSourceFiles) {
                sourceFile.delete();
            }
            mSessionSourceFiles.clear();
        }
        mImageView = null;
        mContainerPager = null;
        mTextImagePager = null;
        mButtonPrevImage = null;
        mButtonNextImage = null;
        mSliderBlurStrength = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mDepthEngine = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_prev_image) {
            switchImage(-1);
        } else if (id == R.id.button_next_image) {
            switchImage(1);
        } else if (id == R.id.button_save) {
            saveImages();
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
        editorState.mUris = new ArrayList<>();
        editorState.mImageEditStates = new ArrayList<>();
        if (mNavRoute != null && mNavRoute.getRouteArgs() instanceof UriList) {
            editorState.mUris.addAll(((UriList) mNavRoute.getRouteArgs()).getUris());
        }
        for (int i = 0; i < editorState.mUris.size(); i++) {
            editorState.mImageEditStates.add(new ImageEditState());
        }
        return editorState;
    }

    private void onEditorStateChanged(EditorState editorState) {
        Activity activity = mNavigator.getActivity();
        updatePager(activity, editorState);
        updateStrengthControls(editorState);
        if (mIsPreparing) {
            // the preparation pipeline shows the progress dialog and the image
            // is displayed when it finishes
            return;
        }
        displayCurrentImage(editorState);
    }

    private void updatePager(Activity activity, EditorState editorState) {
        int total = editorState.mUris.size();
        if (total > 1) {
            mContainerPager.setVisibility(View.VISIBLE);
            mTextImagePager.setText(activity.getString(R.string.image_pager_,
                    editorState.mCurrentIndex + 1, total));
            mButtonPrevImage.setEnabled(editorState.mCurrentIndex > 0);
            mButtonNextImage.setEnabled(editorState.mCurrentIndex < total - 1);
        } else {
            mContainerPager.setVisibility(View.GONE);
        }
    }

    private void displayCurrentImage(EditorState editorState) {
        int index = editorState.mCurrentIndex;
        int strength = getImageStrength(editorState, index);
        ResultCache resultCache = mResultCache;
        if (resultCache != null && resultCache.mImageIndex == index
                && resultCache.mStrength == strength) {
            showResult(resultCache.mResult);
            return;
        }
        if (editorState.mImageEditStates.get(index).getPrepared() != null) {
            // prepared already (a switch back or a snapshot restore), the
            // depth map is either engine-cached or re-estimated just in time
            prepareResult(editorState, index, strength);
        } else {
            // only reachable when the createView preparation completed without
            // covering this image, retry the preparation then
            startPreparation(editorState);
        }
    }

    /**
     * Prepare the images that have not been prepared yet (prepared == null),
     * copies each content uri to a private cache file and runs the depth
     * inference at the preview resolution of each image (engine-cached by the
     * image index)
     */
    private void startPreparation(EditorState editorState) {
        ArrayList<Integer> pendingIndexes = new ArrayList<>();
        for (int i = 0; i < editorState.mUris.size(); i++) {
            if (editorState.mImageEditStates.get(i).getPrepared() == null) {
                pendingIndexes.add(i);
            }
        }
        if (pendingIndexes.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        int total = pendingIndexes.size();
        mIsPreparing = true;
        mPreparationProgressCount = 0;
        mProgressDialog.show(true, total,
                activity.getString(R.string.loading_images_progress,
                        mPreparationProgressCount, total));
        mRxDisposer.add("startPreparation", Flowable.fromIterable(pendingIndexes)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> prepareImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        preparedImage -> {
                            onImagePrepared(editorState, preparedImage);
                            mPreparationProgressCount++;
                            mProgressDialog.update(mPreparationProgressCount, total,
                                    activity.getString(R.string.loading_images_progress,
                                            mPreparationProgressCount, total));
                        },
                        throwable -> {
                            mIsPreparing = false;
                            mProgressDialog.hide();
                            // a catastrophic scheduler error must not leave a blank editor,
                            // the remaining images keep their states untouched
                            displayCurrentImage(mEditorState.getValue());
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            mIsPreparing = false;
                            mProgressDialog.hide();
                            displayCurrentImage(mEditorState.getValue());
                        }
                ));
    }

    /**
     * Decode the downscaled preview of the image at the given index, copy the
     * content uri to a private cache file and estimate its depth map. Runs on
     * a background thread.
     */
    private PreparedImage prepareImageTask(EditorState editorState, int index) {
        PreparedImage preparedImage = new PreparedImage(index);
        Bitmap preview = null;
        try {
            Uri sourceUri = Uri.parse(editorState.mUris.get(index));
            // content uri grants are ephemeral and die with the process, copy the
            // image to a private cache file once so the editor still works after a
            // process restart (the snapshot persists the file uri, see onImagePrepared)
            if (!"file".equals(sourceUri.getScheme())) {
                preparedImage.mSourceFile = mFileHelper.copySourceToTempFile(
                        EDITOR_SOURCE_PREFIX + index + ".jpg", sourceUri);
            }
            Uri uri = preparedImage.mSourceFile != null
                    ? Uri.fromFile(preparedImage.mSourceFile) : sourceUri;
            preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
            // the depth map is warmed under the image index inside the
            // engine (no handle is returned), the display and the strength
            // toggles reuse it instead of re-running the inference
            mDepthEngine.prepareDepth(index, preview);
        } catch (Exception | OutOfMemoryError e) {
            // keep the editor usable for the remaining images, the image is
            // marked prepared anyway so the display/save pipelines retry the
            // depth estimation just in time instead of looping the preparation
            mLogger.e(TAG, e.getMessage(), e);
        } finally {
            if (preview != null) {
                preview.recycle();
            }
        }
        return preparedImage;
    }

    private void onImagePrepared(EditorState editorState, PreparedImage preparedImage) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(preparedImage.mIndex);
        imageEditState.setPrepared(Boolean.TRUE);
        if (preparedImage.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri on the
            // main thread before the state emission, so the snapshot persists the
            // file uri and every later step (preview rebuild, save, share, restore)
            // reads the copy
            editorState.mUris.set(preparedImage.mIndex,
                    Uri.fromFile(preparedImage.mSourceFile).toString());
            if (mSessionSourceFiles == null) {
                // belt-and-braces against a deserialized instance (the list is
                // normally re-initialized in provideComponent)
                mSessionSourceFiles = new ArrayList<>();
            }
            mSessionSourceFiles.add(preparedImage.mSourceFile);
        }
        mEditorState.onNext(editorState);
    }

    /**
     * Composite the displayed image at the preview resolution, a rebuild only
     * re-decodes the preview and re-blends (the depth map stays cached), the
     * inference is not re-run
     */
    private void prepareResult(EditorState editorState, int index, int strength) {
        Activity activity = mNavigator.getActivity();
        mProgressDialog.show(false, 0, activity.getString(R.string.loading_image));
        mRxDisposer.add("prepareResult", Single.fromCallable(() ->
                        buildResult(editorState, index, strength))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((resultCache, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (resultCache.mImageIndex != currentState.mCurrentIndex
                            || resultCache.mStrength != getImageStrength(currentState, resultCache.mImageIndex)) {
                        // the user has switched to another image or config meanwhile
                        if (resultCache.mResult != null) {
                            resultCache.mResult.recycle();
                        }
                        return;
                    }
                    if (mResultCache != null && mResultCache.mResult != resultCache.mResult) {
                        mResultCache.mResult.recycle();
                    }
                    mResultCache = resultCache;
                    showResult(resultCache.mResult);
                }));
    }

    /**
     * Build the display result of the image at the given index. Runs on a
     * background thread: decodes the preview and blends the depth-aware
     * bokeh over the whole image, the engine resolves the per-key cached
     * map internally (estimated just in time when the key has no entry yet).
     */
    private ResultCache buildResult(EditorState editorState, int index, int strength)
            throws IOException {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        try {
            float previewScale = computePreviewScale(uri, preview);
            Bitmap result = mDepthEngine.lensBlurBackground(index, preview,
                    strength, previewScale);
            preview.recycle();
            preview = null;
            return new ResultCache(index, strength, result);
        } catch (Exception | OutOfMemoryError e) {
            if (preview != null) {
                preview.recycle();
            }
            throw e;
        }
    }

    /**
     * @return the preview to full resolution scale factor of the given image,
     * 1.0 as the fallback when the full resolution dimension cannot be read
     */
    private float computePreviewScale(Uri uri, Bitmap preview) {
        try {
            int[] fullDimension = mImageHelper.decodeImageDimension(uri);
            int fullMaxDim = Math.max(fullDimension[0], fullDimension[1]);
            int previewMaxDim = Math.max(preview.getWidth(), preview.getHeight());
            if (fullMaxDim > 0) {
                return (float) previewMaxDim / fullMaxDim;
            }
        } catch (Exception e) {
            // without the full resolution dimension the preview blur kernel is
            // computed from the preview size, so the relative blur strength can
            // diverge from the saved full resolution result (the kernel clamping
            // applies at different boundaries), it stays usable because the
            // engine clamps the kernel size
            mLogger.e(TAG, "Failed to read the full resolution dimension of " + uri
                    + ", preview " + preview.getWidth() + "x" + preview.getHeight()
                    + " px, falling back to a preview scale factor of 1.0", e);
        }
        return 1f;
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

    private void switchImage(int direction) {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int nextIndex = editorState.mCurrentIndex + direction;
        if (nextIndex < 0 || nextIndex >= editorState.mUris.size()) {
            return;
        }
        editorState.mCurrentIndex = nextIndex;
        mEditorState.onNext(editorState);
    }

    /**
     * @return the blur strength of the image at the given index, an out of
     * range value falls back to the default strength
     */
    private int getImageStrength(EditorState editorState, int index) {
        int strength = editorState.mImageEditStates.get(index).getStrength();
        if (strength < BlurConfig.STRENGTH_MIN || strength > BlurConfig.STRENGTH_MAX) {
            strength = BlurConfig.STRENGTH_DEFAULT;
        }
        return strength;
    }

    /**
     * Apply the given strength to the currently displayed image so the result
     * cache becomes stale and the preview rebuild triggers through the editor
     * state emission
     */
    private void changeBlurStrength(int strength) {
        if (mProgressDialog.isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int index = editorState.mCurrentIndex;
        if (getImageStrength(editorState, index) == strength) {
            return;
        }
        editorState.mImageEditStates.get(index).setStrength(strength);
        editorState.mHasUnsavedChanges = true;
        mEditorState.onNext(editorState);
    }

    /**
     * Reflect the strength of the currently displayed image on the slider,
     * the programmatic setValue does not fire the slider touch listener, so
     * no feedback guard is needed here
     */
    private void updateStrengthControls(EditorState editorState) {
        if (mSliderBlurStrength == null) {
            return;
        }
        mSliderBlurStrength.setValue(
                getImageStrength(editorState, editorState.mCurrentIndex));
    }

    private NavBackPressResult handleBack() {
        if (mProgressDialog.isWorking()) {
            // a preparation/prepare/save is running, the back press is ignored
            return NavBackPressResult.noPop();
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_lens_blur_changes)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return NavBackPressResult.noPop();
        }
        return NavBackPressResult.pop();
    }

    private void saveImages() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int total = editorState.mUris.size();
        mSaveResults = new ArrayList<>();
        mProgressDialog.show(true, total,
                activity.getString(R.string.saving_progress, 1, total));
        mRxDisposer.add("saveImages", Flowable.range(0, total)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> saveImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        saveResult -> {
                            mSaveResults.add(saveResult);
                            mProgressDialog.update(saveResult.mIndex + 1, total,
                                    activity.getString(R.string.saving_progress,
                                            saveResult.mIndex + 1, total));
                        },
                        throwable -> {
                            mProgressDialog.hide();
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            mProgressDialog.hide();
                            updateUnsavedChangesState(editorState);
                            showSaveSummary(activity, total);
                        }
                ));
    }

    /**
     * Clear the unsaved-changes state through the editor state subject when the
     * whole batch finished without any failure
     */
    private void updateUnsavedChangesState(EditorState editorState) {
        boolean hasFailed = false;
        for (SaveResult saveResult : mSaveResults) {
            if (saveResult.mStatus == SaveResult.STATUS_FAILED) {
                hasFailed = true;
                break;
            }
        }
        if (!hasFailed && editorState.mHasUnsavedChanges) {
            editorState.mHasUnsavedChanges = false;
            mEditorState.onNext(editorState);
        }
    }

    /**
     * Save the image at the given index to the gallery. The engine resolves
     * the per-key cached map internally (just-in-time full resolution
     * estimation when the key has no cached entry, e.g. after a process
     * death restore). Runs on a background thread.
     */
    @SuppressLint("MissingPermission")
    private SaveResult saveImageTask(EditorState editorState, int index) {
        SaveResult saveResult = new SaveResult(index);
        try {
            Uri uri = Uri.parse(editorState.mUris.get(index));
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                resultBitmap = buildLensBlurBitmap(editorState, index, fullBitmap);
                String title = buildSavedImageTitle(index);
                String stringUrl = mMediaHelper.insertImage(resultBitmap, title, title,
                        Bitmap.CompressFormat.JPEG);
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
     * Full resolution lens blur of the given bitmap, shared by the save and
     * the share pipeline. The engine resolves the per-key cached map
     * internally (typically the cached preview-resolution map resized to the
     * full resolution inside the blend; a just-in-time full resolution
     * estimation runs when the key has no cached entry). No depth handle is
     * returned to the page.
     *
     * @return the lens blur result bitmap (ARGB_8888)
     */
    private Bitmap buildLensBlurBitmap(EditorState editorState, int index,
                                       Bitmap fullBitmap) {
        return mDepthEngine.lensBlurBackground(index, fullBitmap,
                getImageStrength(editorState, index), 1f);
    }

    /**
     * @return the gallery file title of the saved image, always JPEG because
     * the depth blend result is opaque
     */
    private String buildSavedImageTitle(int index) {
        return "lensblur_" + (index + 1) + "_" + System.currentTimeMillis() + ".jpg";
    }

    private void showSaveSummary(Activity activity, int total) {
        StringBuilder message = new StringBuilder();
        int savedCount = 0;
        for (SaveResult saveResult : mSaveResults) {
            if (saveResult.mStatus == SaveResult.STATUS_SAVED) {
                savedCount++;
            }
        }
        message.append(activity.getString(R.string.saved_summary, savedCount, total));
        for (SaveResult saveResult : mSaveResults) {
            String imageLabel = activity.getString(R.string.image_number_, saveResult.mIndex + 1);
            if (saveResult.mStatus == SaveResult.STATUS_FAILED) {
                message.append("\n").append(activity.getString(R.string.failed_to_save_, imageLabel));
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_save_result)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                .setCancelable(false)
                .show();
    }

    private void shareImage() {
        if (mProgressDialog.isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int index = editorState.mCurrentIndex;
        mProgressDialog.show(false, 0, activity.getString(R.string.preparing_share));
        mRxDisposer.add("shareImage", Single.fromCallable(() ->
                        buildShareFile(editorState, index))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((file, throwable) -> {
                    mProgressDialog.hide();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        mLogger.i(TAG, activity.getString(R.string.share_failed));
                    } else {
                        mLastShareFile = file;
                        UiUtils.shareFile(activity, file,
                                activity.getString(R.string.share),
                                "image/jpeg");
                    }
                }));
    }

    /**
     * Composite the currently displayed image at the full resolution and write
     * it to a temp file to be shared (always JPEG). Runs on a background thread.
     */
    private File buildShareFile(EditorState editorState, int index) {
        try {
            Uri uri = Uri.parse(editorState.mUris.get(index));
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                resultBitmap = buildLensBlurBitmap(editorState, index, fullBitmap);
                return mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg",
                        resultBitmap, Bitmap.CompressFormat.JPEG);
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
