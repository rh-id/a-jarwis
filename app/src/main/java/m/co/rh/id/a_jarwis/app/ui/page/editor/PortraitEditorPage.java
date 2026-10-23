package m.co.rh.id.a_jarwis.app.ui.page.editor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
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
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.UiUtils;
import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.PersonSegEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.PersonSegMask;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * Interactive portrait mode editor.
 * Segments the person of each picked image, the user toggles per image between
 * blurring the background (JPEG result) and removing the background
 * (transparent PNG result) with a blur strength slider for the blur mode.
 * The preview composites run at a downscaled preview resolution, the save/share
 * re-segments at the full resolution before writing the result.
 */
public class PortraitEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "PortraitEditorPage";

    /**
     * Portrait edit mode of an image: the background is blurred, the person
     * stays sharp, the result is exported as JPEG
     */
    public static final int MODE_BLUR_BACKGROUND = 0;
    /**
     * Portrait edit mode of an image: the background is removed (transparent),
     * the result is exported as PNG
     */
    public static final int MODE_REMOVE_BACKGROUND = 1;

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "portraiteditor_share_";
    /**
     * File name prefix of the private cache copy of a picked image, created once
     * per image at segmentation time because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "portrait_editor_source_";

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
         * true when the segmentation found a person, false when it found no
         * person or failed, null means the image has not been segmented yet
         */
        private Boolean mHasPerson;
        /**
         * One of the MODE_ constants of {@link PortraitEditorPage}
         */
        private int mMode;
        /**
         * Blur strength (BlurConfig range), only used by the blur mode
         */
        private int mStrength;

        private Boolean getHasPerson() {
            return mHasPerson;
        }

        private void setHasPerson(Boolean hasPerson) {
            mHasPerson = hasPerson;
        }

        private int getMode() {
            return mMode;
        }

        private void setMode(int mode) {
            mMode = mode;
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
        private final int mMode;
        private final int mStrength;
        private final Bitmap mResult;

        private ResultCache(int imageIndex, int mode, int strength, Bitmap result) {
            mImageIndex = imageIndex;
            mMode = mode;
            mStrength = strength;
            mResult = result;
        }
    }

    /**
     * Segmentation state of a single image, used while the segmentation pipeline is running
     */
    private static class SegmentedImage {
        private final int mIndex;
        /**
         * Private cache copy of the source image created during segmentation when
         * the source uri is not a file uri, null when the source is already a
         * file uri or the copy failed (the original uri keeps being used)
         */
        private File mSourceFile;
        private Boolean mHasPerson;

        private SegmentedImage(int index) {
            mIndex = index;
        }
    }

    /**
     * Save result of a single image, used while the save pipeline is running
     */
    private static class SaveResult {
        private static final int STATUS_SAVED = 1;
        private static final int STATUS_FAILED = 2;
        private static final int STATUS_NO_PERSON = 3;

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
    private transient PersonSegEngine mPersonSegEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient View mContainerPager;
    private transient AppCompatTextView mTextImagePager;
    private transient AppCompatImageButton mButtonPrevImage;
    private transient AppCompatImageButton mButtonNextImage;
    private transient MaterialButtonToggleGroup mTogglePortraitMode;
    private transient Slider mSliderBlurStrength;
    private transient AppCompatTextView mTextNoPerson;
    private transient AlertDialog mProgressDialog;
    private transient ProgressBar mProgressBar;
    private transient AppCompatTextView mTextProgress;

    // runtime
    private transient ResultCache mResultCache;
    /**
     * The composited bitmap currently shown on mImageView,
     * may be the ResultCache bitmap or the raw preview of a no-person image
     */
    private transient Bitmap mDisplayBitmap;
    private transient boolean mIsSegmenting;
    private transient int mSegmentationProgressCount;
    private transient ArrayList<SaveResult> mSaveResults;
    private transient File mLastShareFile;
    /**
     * Private cache copies of the picked images created by this session,
     * never serialized. Deleted on dispose, a process death leaves them in
     * place so the restored snapshot can still read them (stale leftovers are
     * purged by age in a later session)
     */
    private transient ArrayList<File> mSessionSourceFiles;
    /**
     * Guard flag while the mode controls reflect the current image mode
     * programmatically, so the update does not feed back into the change handlers
     */
    private transient boolean mIsUpdatingModeControls;

    public PortraitEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mPersonSegEngine = mSvProvider.get(PersonSegEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level source copy list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_portrait_editor, container, false);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        mImageView = rootView.findViewById(R.id.imageView);
        mContainerPager = rootView.findViewById(R.id.container_pager);
        mTextImagePager = rootView.findViewById(R.id.text_image_pager);
        mButtonPrevImage = rootView.findViewById(R.id.button_prev_image);
        mButtonPrevImage.setOnClickListener(this);
        mButtonNextImage = rootView.findViewById(R.id.button_next_image);
        mButtonNextImage.setOnClickListener(this);
        mTogglePortraitMode = rootView.findViewById(R.id.toggle_portrait_mode);
        mTogglePortraitMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                // only the newly checked button selects the mode
                return;
            }
            if (checkedId == R.id.button_mode_blur) {
                changeMode(MODE_BLUR_BACKGROUND);
            } else if (checkedId == R.id.button_mode_remove) {
                changeMode(MODE_REMOVE_BACKGROUND);
            }
        });
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
                // applied on stop tracking so the blurred background is not
                // rebuilt on every tick
                EditorState editorState = mEditorState.getValue();
                if (editorState == null || editorState.mCurrentIndex != mTrackedImageIndex) {
                    // the user switched to another image meanwhile, skip the apply
                    return;
                }
                changeBlurStrength(Math.round(slider.getValue()));
            }
        });
        mTextNoPerson = rootView.findViewById(R.id.text_no_person);
        View saveButton = rootView.findViewById(R.id.button_save);
        saveButton.setOnClickListener(this);
        View shareButton = rootView.findViewById(R.id.button_share);
        shareButton.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        if (mEditorState.getValue() == null) {
            // first open, parse the picked image uris and segment each image
            mEditorState.onNext(parseRouteArgs());
        }
        // segment the images whose person state has not been decided yet
        // (mHasPerson == null), on restore (e.g. process death in the middle of
        // the segmentation) only the pending images are re-segmented, the
        // segmented ones keep their persisted states
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
            if (mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
            }
            mProgressDialog = null;
        }
        mProgressBar = null;
        mTextProgress = null;
        if (mResultCache != null && mResultCache.mResult != null
                && mResultCache.mResult != mDisplayBitmap) {
            mResultCache.mResult.recycle();
        }
        mResultCache = null;
        if (mDisplayBitmap != null) {
            mDisplayBitmap.recycle();
            mDisplayBitmap = null;
        }
        if (mPersonSegEngine != null) {
            // the session masks are released so the same keys of a later
            // session never read the masks of this session's images
            mPersonSegEngine.releaseAllMasks();
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
        mContainerPager = null;
        mTextImagePager = null;
        mButtonPrevImage = null;
        mButtonNextImage = null;
        mTogglePortraitMode = null;
        mSliderBlurStrength = null;
        mTextNoPerson = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mPersonSegEngine = null;
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
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        handleBack();
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
        updateModeControls(editorState);
        if (mIsSegmenting) {
            // the segmentation pipeline shows the progress dialog and the image
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
        int mode = getImageMode(editorState, index);
        int strength = getImageStrength(editorState, index);
        ResultCache resultCache = mResultCache;
        if (resultCache != null && resultCache.mImageIndex == index
                && resultCache.mMode == mode && resultCache.mStrength == strength) {
            showResult(resultCache.mResult);
            updateNoPersonNote(editorState);
            return;
        }
        if (editorState.mImageEditStates.get(index).getHasPerson() != null) {
            // segmented already (a switch back or a snapshot restore), the
            // mask is either cached or the image was marked without a person
            prepareResult(editorState, index, mode, strength);
        } else {
            // only reachable when the createView segmentation completed without
            // covering this image, retry the segmentation then
            startSegmentation(editorState);
        }
    }

    /**
     * Segment the images that have not been segmented yet (hasPerson == null),
     * runs the PP-HumanSeg inference at the preview resolution of each image
     * and caches its person mask
     */
    private void startSegmentation(EditorState editorState) {
        ArrayList<Integer> pendingIndexes = new ArrayList<>();
        for (int i = 0; i < editorState.mUris.size(); i++) {
            if (editorState.mImageEditStates.get(i).getHasPerson() == null) {
                pendingIndexes.add(i);
            }
        }
        if (pendingIndexes.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        int total = pendingIndexes.size();
        mIsSegmenting = true;
        mSegmentationProgressCount = 0;
        showProgressDialog(true, total,
                activity.getString(R.string.detecting_people_progress,
                        mSegmentationProgressCount, total));
        mRxDisposer.add("startSegmentation", Flowable.fromIterable(pendingIndexes)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> segmentImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        segmentedImage -> {
                            onImageSegmented(editorState, segmentedImage);
                            mSegmentationProgressCount++;
                            updateProgressDialog(mSegmentationProgressCount, total,
                                    activity.getString(R.string.detecting_people_progress,
                                            mSegmentationProgressCount, total));
                        },
                        throwable -> {
                            mIsSegmenting = false;
                            hideProgressDialog();
                            // a catastrophic scheduler error must not leave a blank editor,
                            // the remaining images keep their states untouched
                            displayCurrentImage(mEditorState.getValue());
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            mIsSegmenting = false;
                            hideProgressDialog();
                            displayCurrentImage(mEditorState.getValue());
                        }
                ));
    }

    /**
     * Decode the downscaled preview of the image at the given index and segment
     * its person. Runs on a background thread.
     */
    private SegmentedImage segmentImageTask(EditorState editorState, int index) {
        SegmentedImage segmentedImage = new SegmentedImage(index);
        Bitmap preview = null;
        try {
            Uri sourceUri = Uri.parse(editorState.mUris.get(index));
            // content uri grants are ephemeral and die with the process, copy the
            // image to a private cache file once so the editor still works after a
            // process restart (the snapshot persists the file uri, see onImageSegmented)
            if (!"file".equals(sourceUri.getScheme())) {
                segmentedImage.mSourceFile = copySourceToTempFile(sourceUri, index);
            }
            Uri uri = segmentedImage.mSourceFile != null
                    ? Uri.fromFile(segmentedImage.mSourceFile) : sourceUri;
            preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
            // the mask is cached by the image index inside the engine, the
            // display and the config toggles reuse it instead of re-running
            // the inference
            PersonSegMask mask = mPersonSegEngine.getMask(index, preview);
            segmentedImage.mHasPerson = mPersonSegEngine.hasPerson(mask);
        } catch (Exception | OutOfMemoryError e) {
            // keep the editor usable for the remaining images, the persisted
            // hasPerson=false marks the image as segmented-without-person so it
            // shows the original and is noted at save/share
            segmentedImage.mHasPerson = Boolean.FALSE;
            mLogger.e(TAG, e.getMessage(), e);
        } finally {
            if (preview != null) {
                preview.recycle();
            }
        }
        return segmentedImage;
    }

    /**
     * Copy the given source image to a private cache temp file, a raw buffered
     * byte copy so the original quality and EXIF stay intact for the save path.
     *
     * @param sourceUri ephemeral source uri of the picked image
     * @param index     image index, used for the temp file name
     * @return the created temp file or null when the copy failed
     * (the original uri keeps being used, same as before the copy existed)
     */
    private File copySourceToTempFile(Uri sourceUri, int index) {
        String fileName = EDITOR_SOURCE_PREFIX + index + ".jpg";
        try {
            String lastSegment = sourceUri.getLastPathSegment();
            if (lastSegment != null) {
                int dotIndex = lastSegment.lastIndexOf('.');
                if (dotIndex >= 0) {
                    String extension = lastSegment.substring(dotIndex + 1);
                    // keep the original extension only when it is a plausible one,
                    // the file name must stay a safe single path segment
                    if (!extension.isEmpty() && extension.length() <= 4
                            && extension.matches("[A-Za-z0-9]+")) {
                        fileName = EDITOR_SOURCE_PREFIX + index + "." + extension;
                    }
                }
            }
            return mFileHelper.createTempFile(fileName, sourceUri);
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            return null;
        }
    }

    private void onImageSegmented(EditorState editorState, SegmentedImage segmentedImage) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(segmentedImage.mIndex);
        imageEditState.setHasPerson(segmentedImage.mHasPerson);
        if (segmentedImage.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri on the
            // main thread before the state emission, so the snapshot persists the
            // file uri and every later step (preview rebuild, save, share, restore)
            // reads the copy
            editorState.mUris.set(segmentedImage.mIndex,
                    Uri.fromFile(segmentedImage.mSourceFile).toString());
            if (mSessionSourceFiles == null) {
                // belt-and-braces against a deserialized instance (the list is
                // normally re-initialized in provideComponent)
                mSessionSourceFiles = new ArrayList<>();
            }
            mSessionSourceFiles.add(segmentedImage.mSourceFile);
        }
        mEditorState.onNext(editorState);
    }

    /**
     * Composite the displayed image at the preview resolution, a rebuild only
     * re-decodes the preview and re-composites (the mask stays cached), the
     * inference is not re-run
     */
    private void prepareResult(EditorState editorState, int index, int mode, int strength) {
        Activity activity = mNavigator.getActivity();
        showProgressDialog(false, 0, activity.getString(R.string.loading_image));
        mRxDisposer.add("prepareResult", Single.fromCallable(() ->
                        buildResult(editorState, index, mode, strength))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((resultCache, throwable) -> {
                    hideProgressDialog();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (resultCache.mImageIndex != currentState.mCurrentIndex
                            || resultCache.mMode != getImageMode(currentState, resultCache.mImageIndex)
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
                    updateNoPersonNote(currentState);
                }));
    }

    /**
     * Build the display result of the image at the given index. Runs on a
     * background thread: decodes the preview, reads the cached mask (segmented
     * just in time when the cache has no entry yet) and composites the mode.
     */
    private ResultCache buildResult(EditorState editorState, int index, int mode, int strength)
            throws IOException {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        Boolean hasPerson = editorState.mImageEditStates.get(index).getHasPerson();
        try {
            float previewScale = computePreviewScale(uri, preview);
            Bitmap result;
            if (Boolean.FALSE.equals(hasPerson)) {
                // segmented as no-person (or the segmentation failed), the
                // display shows the original untouched preview, the note explains
                result = preview;
                preview = null; // the ownership moved to the ResultCache
            } else {
                PersonSegMask mask = mPersonSegEngine.getMask(index, preview);
                result = mode == MODE_REMOVE_BACKGROUND
                        ? mPersonSegEngine.removeBackground(preview, mask)
                        : mPersonSegEngine.blurBackground(preview, mask,
                        new BlurConfig(BlurConfig.SHAPE_ELLIPSE, strength), previewScale);
                preview.recycle();
                preview = null;
            }
            return new ResultCache(index, mode, strength, result);
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

    private void updateNoPersonNote(EditorState editorState) {
        ImageEditState imageEditState =
                editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (Boolean.FALSE.equals(imageEditState.getHasPerson())) {
            mTextNoPerson.setVisibility(View.VISIBLE);
        } else {
            mTextNoPerson.setVisibility(View.GONE);
        }
    }

    private void switchImage(int direction) {
        if (isWorking()) {
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
     * @return the mode of the image at the given index, an unknown mode
     * (snapshot persisted by the previous build) falls back to the blur mode
     */
    private int getImageMode(EditorState editorState, int index) {
        int mode = editorState.mImageEditStates.get(index).getMode();
        if (mode != MODE_BLUR_BACKGROUND && mode != MODE_REMOVE_BACKGROUND) {
            mode = MODE_BLUR_BACKGROUND;
        }
        return mode;
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
     * Apply the given mode to the currently displayed image so the result
     * cache becomes stale and the preview rebuild triggers through the editor
     * state emission
     */
    private void changeMode(int mode) {
        if (isWorking() || mIsUpdatingModeControls) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int index = editorState.mCurrentIndex;
        if (getImageMode(editorState, index) == mode) {
            return;
        }
        editorState.mImageEditStates.get(index).setMode(mode);
        editorState.mHasUnsavedChanges = true;
        mEditorState.onNext(editorState);
    }

    /**
     * Apply the given strength to the currently displayed image so the result
     * cache becomes stale and the preview rebuild triggers through the editor
     * state emission
     */
    private void changeBlurStrength(int strength) {
        if (isWorking() || mIsUpdatingModeControls) {
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
     * Reflect the mode of the currently displayed image in the mode controls,
     * the programmatic updates must not feed back into the change handlers
     */
    private void updateModeControls(EditorState editorState) {
        if (mTogglePortraitMode == null || mSliderBlurStrength == null) {
            return;
        }
        int mode = getImageMode(editorState, editorState.mCurrentIndex);
        int strength = getImageStrength(editorState, editorState.mCurrentIndex);
        mIsUpdatingModeControls = true;
        try {
            if (mode == MODE_REMOVE_BACKGROUND) {
                mTogglePortraitMode.check(R.id.button_mode_remove);
            } else {
                mTogglePortraitMode.check(R.id.button_mode_blur);
            }
            // the strength config is useless for the remove mode
            mSliderBlurStrength.setVisibility(mode == MODE_BLUR_BACKGROUND
                    ? View.VISIBLE : View.GONE);
            mSliderBlurStrength.setValue(strength);
        } finally {
            mIsUpdatingModeControls = false;
        }
    }

    private void handleBack() {
        if (isWorking()) {
            // a segmentation/prepare/save is running, the back press is ignored
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_portrait_changes)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            mNavigator.pop();
        }
    }

    private void saveImages() {
        if (isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        int total = editorState.mUris.size();
        mSaveResults = new ArrayList<>();
        showProgressDialog(true, total,
                activity.getString(R.string.saving_progress, 1, total));
        mRxDisposer.add("saveImages", Flowable.range(0, total)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> saveImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        saveResult -> {
                            mSaveResults.add(saveResult);
                            updateProgressDialog(saveResult.mIndex + 1, total,
                                    activity.getString(R.string.saving_progress,
                                            saveResult.mIndex + 1, total));
                        },
                        throwable -> {
                            hideProgressDialog();
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            hideProgressDialog();
                            updateUnsavedChangesState(editorState);
                            showSaveSummary(activity, total);
                        }
                ));
    }

    /**
     * Clear the unsaved-changes state through the editor state subject when the
     * whole batch finished without any failure, a saved or skipped-with-note
     * image counts as a non-failure
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
     * Save the image at the given index to the gallery in the configured mode.
     * The person mask is computed at the full resolution (never serialized,
     * always rebuilt). Runs on a background thread.
     */
    @SuppressLint("MissingPermission")
    private SaveResult saveImageTask(EditorState editorState, int index) {
        SaveResult saveResult = new SaveResult(index);
        try {
            Uri uri = Uri.parse(editorState.mUris.get(index));
            ImageEditState imageEditState = editorState.mImageEditStates.get(index);
            if (Boolean.FALSE.equals(imageEditState.getHasPerson())) {
                saveResult.mStatus = SaveResult.STATUS_NO_PERSON;
                return saveResult;
            }
            int mode = getImageMode(editorState, index);
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                // the full resolution mask is rebuilt (the cached preview masks
                // are the wrong resolution), it is never worth caching
                PersonSegMask mask = mPersonSegEngine.computeMask(fullBitmap);
                try {
                    if (!mPersonSegEngine.hasPerson(mask)) {
                        saveResult.mStatus = SaveResult.STATUS_NO_PERSON;
                        return saveResult;
                    }
                    resultBitmap = mode == MODE_REMOVE_BACKGROUND
                            ? mPersonSegEngine.removeBackground(fullBitmap, mask)
                            : mPersonSegEngine.blurBackground(fullBitmap, mask,
                            new BlurConfig(BlurConfig.SHAPE_ELLIPSE,
                                    getImageStrength(editorState, index)), 1f);
                } finally {
                    mask.release();
                }
                String title = buildSavedImageTitle(index, mode);
                String stringUrl = mMediaHelper.insertImage(resultBitmap, title, title,
                        mode == MODE_REMOVE_BACKGROUND
                                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG);
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
     * @return the gallery file title of the saved image, the extension follows
     * the mode (transparent PNG in the remove mode) so the media store stores
     * the alpha channel with the matching mime
     */
    private String buildSavedImageTitle(int index, int mode) {
        return "portrait_" + (index + 1) + "_" + System.currentTimeMillis()
                + (mode == MODE_REMOVE_BACKGROUND ? ".png" : ".jpg");
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
            } else if (saveResult.mStatus == SaveResult.STATUS_NO_PERSON) {
                message.append("\n").append(activity.getString(
                        R.string.no_person_detected_, imageLabel));
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
        if (isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        ImageEditState imageEditState =
                editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (Boolean.FALSE.equals(imageEditState.getHasPerson())) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_share_no_portrait)
                    .setMessage(R.string.msg_share_no_portrait)
                    .setPositiveButton(R.string.share, (dialog, which) ->
                            proceedShareImage(activity))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            proceedShareImage(activity);
        }
    }

    private void proceedShareImage(Activity activity) {
        EditorState editorState = mEditorState.getValue();
        int index = editorState.mCurrentIndex;
        int mode = getImageMode(editorState, index);
        showProgressDialog(false, 0, activity.getString(R.string.preparing_share));
        mRxDisposer.add("shareImage", Single.fromCallable(() ->
                        buildShareFile(editorState, index))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((file, throwable) -> {
                    hideProgressDialog();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        mLogger.i(TAG, activity.getString(R.string.share_failed));
                    } else {
                        mLastShareFile = file;
                        UiUtils.shareFile(activity, file,
                                activity.getString(R.string.share),
                                mode == MODE_REMOVE_BACKGROUND ? "image/png" : "image/jpeg");
                    }
                }));
    }

    /**
     * Composite the currently displayed image at the full resolution and write
     * it to a temp file to be shared (JPEG in the blur mode, transparent PNG in
     * the remove mode). A no-person image shares the original. Runs on a
     * background thread.
     */
    private File buildShareFile(EditorState editorState, int index) {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        ImageEditState imageEditState = editorState.mImageEditStates.get(index);
        boolean isPng = getImageMode(editorState, index) == MODE_REMOVE_BACKGROUND;
        try {
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap resultBitmap = null;
            try {
                Bitmap bitmapToShare = fullBitmap;
                if (!Boolean.FALSE.equals(imageEditState.getHasPerson())) {
                    // the full resolution mask is rebuilt for every share
                    PersonSegMask mask = mPersonSegEngine.computeMask(fullBitmap);
                    try {
                        if (mPersonSegEngine.hasPerson(mask)) {
                            resultBitmap = isPng
                                    ? mPersonSegEngine.removeBackground(fullBitmap, mask)
                                    : mPersonSegEngine.blurBackground(fullBitmap, mask,
                                    new BlurConfig(BlurConfig.SHAPE_ELLIPSE,
                                            getImageStrength(editorState, index)), 1f);
                            bitmapToShare = resultBitmap;
                        }
                    } finally {
                        mask.release();
                    }
                }
                File outFile = mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis()
                                + (isPng ? ".png" : ".jpg"), bitmapToShare,
                        isPng ? Bitmap.CompressFormat.PNG
                                : Bitmap.CompressFormat.JPEG);
                return outFile;
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

    private boolean isWorking() {
        return mProgressDialog != null && mProgressDialog.isShowing();
    }

    /**
     * @param determinate true to show a determinate progress bar with the given max,
     *                    false to show an indeterminate one
     */
    private void showProgressDialog(boolean determinate, int max, String message) {
        if (mProgressDialog == null) {
            Activity activity = mNavigator.getActivity();
            View contentView = activity.getLayoutInflater()
                    .inflate(R.layout.dialog_face_editor_progress, null);
            mProgressBar = contentView.findViewById(R.id.progressBar);
            mTextProgress = contentView.findViewById(R.id.text_progress);
            mProgressDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_portrait_mode)
                    .setView(contentView)
                    .setCancelable(false)
                    .create();
            mProgressDialog.show();
        }
        mProgressBar.setIndeterminate(!determinate);
        if (determinate) {
            mProgressBar.setMax(max);
            mProgressBar.setProgress(0);
        }
        mTextProgress.setText(message);
    }

    private void updateProgressDialog(int progress, int max, String message) {
        if (mProgressBar != null) {
            mProgressBar.setIndeterminate(false);
            mProgressBar.setMax(max);
            mProgressBar.setProgress(progress);
        }
        if (mTextProgress != null) {
            mTextProgress.setText(message);
        }
    }

    private void hideProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
        mProgressBar = null;
        mTextProgress = null;
    }
}
