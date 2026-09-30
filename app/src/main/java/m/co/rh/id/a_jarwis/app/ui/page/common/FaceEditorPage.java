package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
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
import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * Interactive face blur editor.
 * Shows each picked image with the detected faces outlined on a downscaled preview,
 * tapping a face toggles its blur. The save/share composites the blur at the
 * full resolution before writing the result to the gallery/temp file.
 */
public class FaceEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener, View.OnTouchListener {

    private static final String TAG = "FaceEditorPage";

    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * Face rect outline stroke width relative to the preview width
     */
    private static final float OUTLINE_WIDTH_SCALE = 0.004f;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "faceeditor_share_";
    /**
     * File name prefix of the private cache copy of a picked image, created once
     * per image at detection time because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "editor_source_";

    /**
     * Serializable editor state of this page,
     * only holds serializable fields (strings, boxed types and primitive arrays)
     * so it survives the navigator snapshot, never hold Uri/Rect/Bitmap here
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
         * Detected face rects in the preview bitmap coordinate {left, top, right, bottom},
         * null means the faces of this image have not been detected yet
         */
        private ArrayList<int[]> mFaceRects;
        /**
         * Blur toggle of each detected face, aligned with mFaceRects
         */
        private ArrayList<Boolean> mBlurFaceList;
        /**
         * Preview bitmap dimension, used to scale the face rects to the full resolution
         */
        private int mPreviewWidth;
        private int mPreviewHeight;
        /**
         * Blur shape and strength of this image, null means the config is not
         * initialized yet (e.g. a snapshot persisted by the previous build),
         * the read path falls back to the last used config
         */
        private BlurConfig mBlurConfig;

        private ArrayList<int[]> getFaceRects() {
            return mFaceRects;
        }

        private void setFaceRects(ArrayList<int[]> faceRects) {
            mFaceRects = faceRects;
        }

        private ArrayList<Boolean> getBlurFaceList() {
            return mBlurFaceList;
        }

        private void setBlurFaceList(ArrayList<Boolean> blurFaceList) {
            mBlurFaceList = blurFaceList;
        }

        private void setPreviewSize(int previewWidth, int previewHeight) {
            mPreviewWidth = previewWidth;
            mPreviewHeight = previewHeight;
        }

        private BlurConfig getBlurConfig() {
            return mBlurConfig;
        }

        private void setBlurConfig(BlurConfig blurConfig) {
            mBlurConfig = blurConfig;
        }
    }

    /**
     * Runtime preview cache of the currently displayed image,
     * never serialized (bitmaps must not be in the navigator snapshot)
     */
    private static class PreviewCache {
        private final int mImageIndex;
        private final BlurConfig mBlurConfig;
        private final Bitmap mPreview;
        private final ArrayList<Bitmap> mBlurredCrops;

        private PreviewCache(int imageIndex, BlurConfig blurConfig,
                             Bitmap preview, ArrayList<Bitmap> blurredCrops) {
            mImageIndex = imageIndex;
            mBlurConfig = blurConfig;
            mPreview = preview;
            mBlurredCrops = blurredCrops;
        }
    }

    /**
     * Detection result of a single image, used while the detection pipeline is running
     */
    private static class DetectedImage {
        private final int mIndex;
        /**
         * Private cache copy of the source image created during detection when
         * the source uri is not a file uri, null when the source is already a
         * file uri or the copy failed (the original uri keeps being used)
         */
        private File mSourceFile;
        private ArrayList<int[]> mFaceRects;
        private ArrayList<Boolean> mBlurFaceList;
        private int mPreviewWidth;
        private int mPreviewHeight;
        private PreviewCache mPreviewCache;

        private DetectedImage(int index) {
            mIndex = index;
        }
    }

    /**
     * Save result of a single image, used while the save pipeline is running
     */
    private static class SaveResult {
        private static final int STATUS_SAVED = 1;
        private static final int STATUS_FAILED = 2;
        private static final int STATUS_NO_FACE = 3;
        private static final int STATUS_NO_FACE_SELECTED = 4;

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
    private transient FaceEngine mFaceEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient View mContainerPager;
    private transient AppCompatTextView mTextImagePager;
    private transient AppCompatImageButton mButtonPrevImage;
    private transient AppCompatImageButton mButtonNextImage;
    private transient MaterialButtonToggleGroup mToggleBlurShape;
    private transient Slider mSliderBlurStrength;
    private transient AppCompatTextView mTextNoFace;
    private transient AlertDialog mProgressDialog;
    private transient ProgressBar mProgressBar;
    private transient AppCompatTextView mTextProgress;

    // runtime
    private transient PreviewCache mPreviewCache;
    private transient Bitmap mDisplayBitmap;
    private transient boolean mIsDetecting;
    private transient int mDetectionProgressCount;
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
     * Last blur shape/strength used by the user in this session (not persisted),
     * the initial config of each newly detected image
     */
    private transient BlurConfig mLastUsedBlurConfig;
    /**
     * Guard flag while the config controls reflect the current image config
     * programmatically, so the update does not feed back into the change handlers
     */
    private transient boolean mIsUpdatingBlurConfigControls;

    public FaceEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mFaceEngine = mSvProvider.get(FaceEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level last used blur config (not persisted), it becomes the
        // initial config of each image detected in this session
        mLastUsedBlurConfig = new BlurConfig();
    }

    // ClickableViewAccessibility is suppressed because onTouch calls performClick
    // when the tap is handled
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_face_editor, container, false);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        mImageView = rootView.findViewById(R.id.imageView);
        mImageView.setOnTouchListener(this);
        mContainerPager = rootView.findViewById(R.id.container_pager);
        mTextImagePager = rootView.findViewById(R.id.text_image_pager);
        mButtonPrevImage = rootView.findViewById(R.id.button_prev_image);
        mButtonPrevImage.setOnClickListener(this);
        mButtonNextImage = rootView.findViewById(R.id.button_next_image);
        mButtonNextImage.setOnClickListener(this);
        mToggleBlurShape = rootView.findViewById(R.id.toggle_blur_shape);
        mToggleBlurShape.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                // only the newly checked button selects the shape
                return;
            }
            if (checkedId == R.id.button_shape_ellipse) {
                changeBlurShape(BlurConfig.SHAPE_ELLIPSE);
            } else if (checkedId == R.id.button_shape_square) {
                changeBlurShape(BlurConfig.SHAPE_SQUARE);
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
                // applied on stop tracking so the crops are not rebuilt on every tick
                EditorState editorState = mEditorState.getValue();
                if (editorState == null || editorState.mCurrentIndex != mTrackedImageIndex) {
                    // the user switched to another image meanwhile, skip the apply
                    return;
                }
                changeBlurStrength(Math.round(slider.getValue()));
            }
        });
        mTextNoFace = rootView.findViewById(R.id.text_no_face);
        View blurAllButton = rootView.findViewById(R.id.button_blur_all);
        blurAllButton.setOnClickListener(this);
        View clearAllButton = rootView.findViewById(R.id.button_clear_all);
        clearAllButton.setOnClickListener(this);
        View saveButton = rootView.findViewById(R.id.button_save);
        saveButton.setOnClickListener(this);
        View shareButton = rootView.findViewById(R.id.button_share);
        shareButton.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        if (mEditorState.getValue() == null) {
            // first open, parse the picked image uris and detect the faces of each image
            mEditorState.onNext(parseRouteArgs());
        }
        // detect the images whose faces have not been detected yet (mFaceRects == null),
        // on restore (e.g. process death in the middle of the detection) only the pending
        // images are re-detected, the detected ones keep their persisted rects and toggles
        // and the subscription above re-decodes their preview without re-detection
        startFaceDetection(mEditorState.getValue());
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
        recyclePreviewCache();
        if (mDisplayBitmap != null) {
            mDisplayBitmap.recycle();
            mDisplayBitmap = null;
        }
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        if (mSessionSourceFiles != null) {
            // best effort deletion of the source copies owned by this session,
            // nothing reads them once the editor page is popped. The list is kept
            // (cleared only) so a straggling detection emission can never NPE
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
        mToggleBlurShape = null;
        mSliderBlurStrength = null;
        mTextNoFace = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mFaceEngine = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
        mLastUsedBlurConfig = null;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_blur_all) {
            setAllFacesBlurred(true);
        } else if (id == R.id.button_clear_all) {
            setAllFacesBlurred(false);
        } else if (id == R.id.button_prev_image) {
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
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (view.getId() == R.id.imageView) {
            int action = motionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                // claiming the down makes the view the touch target
                // so the following up event is delivered back to it
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                toggleFaceAt(motionEvent.getX(), motionEvent.getY());
                view.performClick();
                return true;
            }
        }
        return false;
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
        updateBlurConfigControls(editorState);
        if (mIsDetecting) {
            // the detection pipeline builds the preview cache of the displayed image itself
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
        PreviewCache previewCache = mPreviewCache;
        if (previewCache != null
                && previewCache.mImageIndex == editorState.mCurrentIndex
                && previewCache.mBlurConfig.equals(
                getBlurConfig(editorState, editorState.mCurrentIndex))) {
            compositeAndShow(editorState);
        } else {
            preparePreviewCache(editorState);
        }
    }

    /**
     * Detect the faces of the images that have not been detected yet,
     * an image is pending when its face rects are still null
     */
    private void startFaceDetection(EditorState editorState) {
        ArrayList<Integer> pendingIndexes = new ArrayList<>();
        for (int i = 0; i < editorState.mUris.size(); i++) {
            if (editorState.mImageEditStates.get(i).getFaceRects() == null) {
                pendingIndexes.add(i);
            }
        }
        if (pendingIndexes.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        int total = pendingIndexes.size();
        mIsDetecting = true;
        mDetectionProgressCount = 0;
        showProgressDialog(true, total,
                activity.getString(R.string.detecting_faces_progress,
                        mDetectionProgressCount, total));
        mRxDisposer.add("startFaceDetection", Flowable.fromIterable(pendingIndexes)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> detectImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        detectedImage -> {
                            onImageDetected(editorState, detectedImage);
                            mDetectionProgressCount++;
                            updateProgressDialog(mDetectionProgressCount, total,
                                    activity.getString(R.string.detecting_faces_progress,
                                            mDetectionProgressCount, total));
                        },
                        throwable -> {
                            mIsDetecting = false;
                            hideProgressDialog();
                            // a catastrophic scheduler error must not leave a blank editor,
                            // the preview build degrades gracefully with null rects
                            displayCurrentImage(mEditorState.getValue());
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            mIsDetecting = false;
                            hideProgressDialog();
                            displayCurrentImage(mEditorState.getValue());
                        }
                ));
    }

    /**
     * Decode the downscaled preview of the image at the given index and detect its faces.
     * Runs on a background thread.
     */
    private DetectedImage detectImageTask(EditorState editorState, int index) {
        DetectedImage detectedImage = new DetectedImage(index);
        Bitmap preview = null;
        ArrayList<Bitmap> blurredCrops = null;
        try {
            Uri sourceUri = Uri.parse(editorState.mUris.get(index));
            // content uri grants are ephemeral and die with the process, copy the
            // image to a private cache file once so the editor still works after a
            // process restart (the snapshot persists the file uri, see onImageDetected)
            if (!"file".equals(sourceUri.getScheme())) {
                detectedImage.mSourceFile = copySourceToTempFile(sourceUri, index);
            }
            Uri uri = detectedImage.mSourceFile != null
                    ? Uri.fromFile(detectedImage.mSourceFile) : sourceUri;
            preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
            float previewScale = computePreviewScale(uri, preview);
            List<Rect> detectedRects = mFaceEngine.detectFace(preview);
            ArrayList<int[]> faceRects = new ArrayList<>();
            ArrayList<Boolean> blurFaceList = new ArrayList<>();
            for (Rect rect : detectedRects) {
                Rect clampedRect = clampRect(rect, preview.getWidth(), preview.getHeight());
                faceRects.add(new int[]{clampedRect.left, clampedRect.top,
                        clampedRect.right, clampedRect.bottom});
                blurFaceList.add(Boolean.TRUE);
            }
            detectedImage.mFaceRects = faceRects;
            detectedImage.mBlurFaceList = blurFaceList;
            detectedImage.mPreviewWidth = preview.getWidth();
            detectedImage.mPreviewHeight = preview.getHeight();
            if (index == editorState.mCurrentIndex) {
                // build the preview cache of the displayed image while the preview is decoded
                BlurConfig blurConfig = getBlurConfig(editorState, index);
                blurredCrops = new ArrayList<>();
                for (int[] rectArr : faceRects) {
                    Bitmap faceCrop = mImageHelper.cropBitmap(preview, toRect(rectArr));
                    blurredCrops.add(mFaceEngine.blurFaceCrop(faceCrop, previewScale, blurConfig));
                    faceCrop.recycle();
                }
                detectedImage.mPreviewCache = new PreviewCache(index,
                        new BlurConfig(blurConfig), preview, blurredCrops);
                preview = null; // the ownership moved to the preview cache
            }
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            if (blurredCrops != null) {
                // the preview cache build failed before the cache took ownership,
                // recycle the crops already produced (same pattern as the preview)
                for (Bitmap blurredCrop : blurredCrops) {
                    blurredCrop.recycle();
                }
                blurredCrops.clear();
            }
            // keep the editor usable for the remaining images
            detectedImage.mFaceRects = new ArrayList<>();
            detectedImage.mBlurFaceList = new ArrayList<>();
        } finally {
            if (preview != null) {
                preview.recycle();
            }
        }
        return detectedImage;
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

    private void onImageDetected(EditorState editorState, DetectedImage detectedImage) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(detectedImage.mIndex);
        imageEditState.setFaceRects(detectedImage.mFaceRects);
        imageEditState.setBlurFaceList(detectedImage.mBlurFaceList);
        imageEditState.setPreviewSize(detectedImage.mPreviewWidth, detectedImage.mPreviewHeight);
        if (detectedImage.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri on the
            // main thread before the state emission, so the snapshot persists the
            // file uri and every later step (preview rebuild, save, share, restore)
            // reads the copy
            editorState.mUris.set(detectedImage.mIndex,
                    Uri.fromFile(detectedImage.mSourceFile).toString());
            mSessionSourceFiles.add(detectedImage.mSourceFile);
        }
        if (detectedImage.mPreviewCache != null) {
            recyclePreviewCache();
            mPreviewCache = detectedImage.mPreviewCache;
        }
        mEditorState.onNext(editorState);
    }

    /**
     * Decode the preview of the current image and re-blur its face crops
     * from the persisted rects, the detection is not re-run
     */
    private void preparePreviewCache(EditorState editorState) {
        int index = editorState.mCurrentIndex;
        Activity activity = mNavigator.getActivity();
        showProgressDialog(false, 0, activity.getString(R.string.loading_image));
        mRxDisposer.add("preparePreviewCache", Single.fromCallable(() ->
                        buildPreviewCache(editorState, index))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((previewCache, throwable) -> {
                    hideProgressDialog();
                    if (throwable != null) {
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    if (previewCache.mImageIndex != currentState.mCurrentIndex
                            || !previewCache.mBlurConfig.equals(
                            getBlurConfig(currentState, currentState.mCurrentIndex))) {
                        // the user has switched to another image or config meanwhile
                        recyclePreviewCache(previewCache);
                        return;
                    }
                    recyclePreviewCache();
                    mPreviewCache = previewCache;
                    compositeAndShow(mEditorState.getValue());
                }));
    }

    private PreviewCache buildPreviewCache(EditorState editorState, int index) throws IOException {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        ImageEditState imageEditState = editorState.mImageEditStates.get(index);
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        ArrayList<Bitmap> blurredCrops = null;
        try {
            float previewScale = computePreviewScale(uri, preview);
            BlurConfig blurConfig = getBlurConfig(editorState, index);
            blurredCrops = new ArrayList<>();
            ArrayList<int[]> faceRects = imageEditState.getFaceRects();
            if (faceRects != null) {
                for (int[] rectArr : faceRects) {
                    Bitmap faceCrop = mImageHelper.cropBitmap(preview, toRect(rectArr));
                    blurredCrops.add(mFaceEngine.blurFaceCrop(faceCrop, previewScale, blurConfig));
                    faceCrop.recycle();
                }
            }
            return new PreviewCache(index, new BlurConfig(blurConfig), preview, blurredCrops);
        } catch (Exception | OutOfMemoryError e) {
            if (blurredCrops != null) {
                // the preview cache was never created, recycle the crops already
                // produced before the failure (same pattern as the preview)
                for (Bitmap blurredCrop : blurredCrops) {
                    blurredCrop.recycle();
                }
                blurredCrops.clear();
            }
            preview.recycle();
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
            // without the full resolution dimension the preview crop kernel is
            // computed from the preview face size, so the relative blur strength
            // can diverge from the saved full resolution result (the kernel
            // clamping applies at different boundaries), it stays usable because
            // the engine clamps the kernel size
            mLogger.e(TAG, "Failed to read the full resolution dimension of " + uri
                    + ", preview " + preview.getWidth() + "x" + preview.getHeight()
                    + " px, falling back to a preview scale factor of 1.0", e);
        }
        return 1f;
    }

    /**
     * Composite the preview and the enabled blurred crops then show it.
     * Runs on the main thread, only bitmap drawing happens here so the
     * bitmap lifetime is never shared with the background threads.
     */
    private void compositeAndShow(EditorState editorState) {
        PreviewCache previewCache = mPreviewCache;
        if (previewCache == null || previewCache.mImageIndex != editorState.mCurrentIndex) {
            return;
        }
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        ArrayList<int[]> faceRects = imageEditState.getFaceRects();
        Bitmap displayBitmap = Bitmap.createBitmap(previewCache.mPreview.getWidth(),
                previewCache.mPreview.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(displayBitmap);
        canvas.drawBitmap(previewCache.mPreview, 0, 0, null);
        if (faceRects != null) {
            for (int i = 0; i < faceRects.size(); i++) {
                if (i >= previewCache.mBlurredCrops.size()) {
                    break;
                }
                if (Boolean.TRUE.equals(imageEditState.getBlurFaceList().get(i))) {
                    canvas.drawBitmap(previewCache.mBlurredCrops.get(i),
                            null, toRect(faceRects.get(i)), null);
                }
            }
        }
        drawRectOutlines(canvas, previewCache, faceRects);
        if (mDisplayBitmap != null && mDisplayBitmap != displayBitmap) {
            mDisplayBitmap.recycle();
        }
        mDisplayBitmap = displayBitmap;
        mImageView.setImageBitmap(displayBitmap);
        mImageView.invalidate();
        updateNoFaceNote(editorState);
    }

    /**
     * Draw a thin accent outline on each face rect so the faces stay discoverable/tappable
     */
    private void drawRectOutlines(Canvas canvas, PreviewCache previewCache, ArrayList<int[]> faceRects) {
        if (faceRects == null || faceRects.isEmpty()) {
            return;
        }
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f,
                previewCache.mPreview.getWidth() * OUTLINE_WIDTH_SCALE));
        paint.setColor(ContextCompat.getColor(mImageView.getContext(),
                m.co.rh.id.a_jarwis.base.R.color.red_800));
        for (int[] rectArr : faceRects) {
            canvas.drawRect(toRect(rectArr), paint);
        }
    }

    private void updateNoFaceNote(EditorState editorState) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        ArrayList<int[]> faceRects = imageEditState.getFaceRects();
        if (faceRects == null || faceRects.isEmpty()) {
            mTextNoFace.setVisibility(View.VISIBLE);
        } else {
            mTextNoFace.setVisibility(View.GONE);
        }
    }

    private void toggleFaceAt(float viewX, float viewY) {
        if (isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        PreviewCache previewCache = mPreviewCache;
        if (editorState == null || previewCache == null
                || previewCache.mImageIndex != editorState.mCurrentIndex) {
            return;
        }
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (imageEditState.getFaceRects() == null || imageEditState.getFaceRects().isEmpty()) {
            return;
        }
        // map the view coordinate to the preview bitmap coordinate using the image matrix
        Matrix inverseMatrix = new Matrix();
        mImageView.getImageMatrix().invert(inverseMatrix);
        float[] point = new float[]{viewX, viewY};
        inverseMatrix.mapPoints(point);
        int faceIndex = hitTestFace(imageEditState, Math.round(point[0]), Math.round(point[1]));
        if (faceIndex < 0) {
            return;
        }
        ArrayList<Boolean> blurFaceList = imageEditState.getBlurFaceList();
        blurFaceList.set(faceIndex, !blurFaceList.get(faceIndex));
        editorState.mHasUnsavedChanges = true;
        mEditorState.onNext(editorState);
    }

    /**
     * Hit test the given preview bitmap coordinate against the face rects,
     * each rect is expanded for the test only by 10% of its own width/height
     * (hit slop) so the face edges are easier to tap, the last drawn (topmost)
     * face wins
     */
    private int hitTestFace(ImageEditState imageEditState, int bitmapX, int bitmapY) {
        ArrayList<int[]> faceRects = imageEditState.getFaceRects();
        for (int i = faceRects.size() - 1; i >= 0; i--) {
            int[] rectArr = faceRects.get(i);
            int slopX = (rectArr[2] - rectArr[0]) / 10;
            int slopY = (rectArr[3] - rectArr[1]) / 10;
            Rect hitRect = new Rect(rectArr[0] - slopX, rectArr[1] - slopY,
                    rectArr[2] + slopX, rectArr[3] + slopY);
            if (hitRect.contains(bitmapX, bitmapY)) {
                return i;
            }
        }
        return -1;
    }

    private void setAllFacesBlurred(boolean blurred) {
        if (isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        PreviewCache previewCache = mPreviewCache;
        if (editorState == null || previewCache == null
                || previewCache.mImageIndex != editorState.mCurrentIndex) {
            return;
        }
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (imageEditState.getFaceRects() == null || imageEditState.getFaceRects().isEmpty()) {
            return;
        }
        ArrayList<Boolean> blurFaceList = imageEditState.getBlurFaceList();
        boolean changed = false;
        for (int i = 0; i < blurFaceList.size(); i++) {
            if (blurFaceList.get(i) != blurred) {
                blurFaceList.set(i, blurred);
                changed = true;
            }
        }
        if (changed) {
            editorState.mHasUnsavedChanges = true;
            mEditorState.onNext(editorState);
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
     * @return the blur config of the image at the given index, a missing config
     * (snapshot persisted by the previous build) is initialized from the last
     * used config on first access
     */
    private BlurConfig getBlurConfig(EditorState editorState, int index) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(index);
        BlurConfig blurConfig = imageEditState.getBlurConfig();
        if (blurConfig == null) {
            blurConfig = new BlurConfig(mLastUsedBlurConfig);
            imageEditState.setBlurConfig(blurConfig);
        }
        return blurConfig;
    }

    /**
     * Apply the given shape to the blur config of the currently displayed image,
     * the config is replaced by a new value object (never mutated in place, the
     * old one is still referenced by the preview cache) so the cache becomes
     * stale and the preview rebuild triggers through the editor state emission
     */
    private void changeBlurShape(int shape) {
        if (isWorking() || mIsUpdatingBlurConfigControls) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        BlurConfig currentConfig = getBlurConfig(editorState, editorState.mCurrentIndex);
        if (currentConfig.getShape() == shape) {
            return;
        }
        BlurConfig newConfig = new BlurConfig(currentConfig);
        newConfig.setShape(shape);
        editorState.mImageEditStates.get(editorState.mCurrentIndex).setBlurConfig(newConfig);
        applyBlurConfigChange(editorState, newConfig);
    }

    /**
     * Apply the given strength to the blur config of the currently displayed image,
     * the config is replaced by a new value object (never mutated in place, the
     * old one is still referenced by the preview cache) so the cache becomes
     * stale and the preview rebuild triggers through the editor state emission
     */
    private void changeBlurStrength(int strength) {
        if (isWorking() || mIsUpdatingBlurConfigControls) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        BlurConfig currentConfig = getBlurConfig(editorState, editorState.mCurrentIndex);
        if (currentConfig.getStrength() == strength) {
            return;
        }
        BlurConfig newConfig = new BlurConfig(currentConfig);
        newConfig.setStrength(strength);
        editorState.mImageEditStates.get(editorState.mCurrentIndex).setBlurConfig(newConfig);
        applyBlurConfigChange(editorState, newConfig);
    }

    /**
     * Common tail of a blur config change: remember the config as the last used
     * one of this session (not persisted) and mark the editor state with
     * unsaved changes so the current image preview cache is rebuilt with the
     * new config
     */
    private void applyBlurConfigChange(EditorState editorState, BlurConfig blurConfig) {
        mLastUsedBlurConfig = new BlurConfig(blurConfig);
        editorState.mHasUnsavedChanges = true;
        mEditorState.onNext(editorState);
    }

    /**
     * Reflect the blur config of the currently displayed image in the config controls,
     * the programmatic updates must not feed back into the change handlers
     */
    private void updateBlurConfigControls(EditorState editorState) {
        if (mToggleBlurShape == null || mSliderBlurStrength == null) {
            return;
        }
        BlurConfig blurConfig = getBlurConfig(editorState, editorState.mCurrentIndex);
        mIsUpdatingBlurConfigControls = true;
        try {
            if (blurConfig.getShape() == BlurConfig.SHAPE_SQUARE) {
                mToggleBlurShape.check(R.id.button_shape_square);
            } else {
                mToggleBlurShape.check(R.id.button_shape_ellipse);
            }
            mSliderBlurStrength.setValue(blurConfig.getStrength());
        } finally {
            mIsUpdatingBlurConfigControls = false;
        }
    }

    private void handleBack() {
        if (isWorking()) {
            // a detection/save/prepare is running, the back press is ignored
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_changes)
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
     * Save the image at the given index to the gallery when it has at least one
     * face to be blurred. Runs on a background thread.
     */
    @SuppressLint("MissingPermission")
    private SaveResult saveImageTask(EditorState editorState, int index) {
        SaveResult saveResult = new SaveResult(index);
        try {
            Uri uri = Uri.parse(editorState.mUris.get(index));
            ImageEditState imageEditState = editorState.mImageEditStates.get(index);
            if (imageEditState.getFaceRects() == null || imageEditState.getFaceRects().isEmpty()) {
                saveResult.mStatus = SaveResult.STATUS_NO_FACE;
                return saveResult;
            }
            ArrayList<Rect> enabledRects = collectEnabledRects(imageEditState);
            if (enabledRects.isEmpty()) {
                saveResult.mStatus = SaveResult.STATUS_NO_FACE_SELECTED;
                return saveResult;
            }
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap blurredBitmap = null;
            try {
                ArrayList<Rect> fullRects = scaleRectsToFull(enabledRects, imageEditState,
                        fullBitmap.getWidth(), fullBitmap.getHeight());
                blurredBitmap = mFaceEngine.blurFacesAt(fullBitmap, fullRects,
                        getBlurConfig(editorState, index));
                String title = buildSavedImageTitle(index);
                String stringUrl = mMediaHelper.insertImage(blurredBitmap, title, title);
                if (stringUrl == null) {
                    saveResult.mStatus = SaveResult.STATUS_FAILED;
                } else {
                    saveResult.mStatus = SaveResult.STATUS_SAVED;
                }
            } finally {
                fullBitmap.recycle();
                if (blurredBitmap != null) {
                    blurredBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            saveResult.mStatus = SaveResult.STATUS_FAILED;
        }
        return saveResult;
    }

    private String buildSavedImageTitle(int index) {
        return "blur_" + (index + 1) + "_" + System.currentTimeMillis() + ".jpg";
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
            } else if (saveResult.mStatus == SaveResult.STATUS_NO_FACE) {
                message.append("\n").append(activity.getString(R.string.no_face_detected_, imageLabel));
            } else if (saveResult.mStatus == SaveResult.STATUS_NO_FACE_SELECTED) {
                message.append("\n").append(activity.getString(R.string.no_face_selected, imageLabel));
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
        if (hasNoBlurredFace(editorState.mImageEditStates.get(editorState.mCurrentIndex))) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_share_no_blur)
                    .setMessage(R.string.msg_share_no_blur)
                    .setPositiveButton(R.string.share, (dialog, which) ->
                            proceedShareImage(activity))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            proceedShareImage(activity);
        }
    }

    /**
     * @return true when the given image edit state has zero detected faces
     * or zero faces toggled to be blurred
     */
    private boolean hasNoBlurredFace(ImageEditState imageEditState) {
        return collectEnabledRects(imageEditState).isEmpty();
    }

    private void proceedShareImage(Activity activity) {
        EditorState editorState = mEditorState.getValue();
        int index = editorState.mCurrentIndex;
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
                                activity.getString(R.string.share), "image/jpeg");
                    }
                }));
    }

    /**
     * Composite the blur of the currently displayed image at the full resolution
     * and write it to a temp JPEG file to be shared. Runs on a background thread.
     */
    private File buildShareFile(EditorState editorState, int index) {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        ImageEditState imageEditState = editorState.mImageEditStates.get(index);
        ArrayList<Rect> enabledRects = collectEnabledRects(imageEditState);
        try {
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap blurredBitmap = null;
            try {
                Bitmap bitmapToSave = fullBitmap;
                if (!enabledRects.isEmpty()) {
                    ArrayList<Rect> fullRects = scaleRectsToFull(enabledRects, imageEditState,
                            fullBitmap.getWidth(), fullBitmap.getHeight());
                    blurredBitmap = mFaceEngine.blurFacesAt(fullBitmap, fullRects,
                            getBlurConfig(editorState, index));
                    bitmapToSave = blurredBitmap;
                }
                File outFile = mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg", bitmapToSave);
                return outFile;
            } finally {
                fullBitmap.recycle();
                if (blurredBitmap != null) {
                    blurredBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError) instead of
            // delivering them to the error consumer, wrap it into a runtime exception
            // so it is handled as a share failure
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * @return the face rects of the image that are toggled to be blurred
     */
    private ArrayList<Rect> collectEnabledRects(ImageEditState imageEditState) {
        ArrayList<Rect> enabledRects = new ArrayList<>();
        ArrayList<int[]> faceRects = imageEditState.getFaceRects();
        if (faceRects != null) {
            for (int i = 0; i < faceRects.size(); i++) {
                if (Boolean.TRUE.equals(imageEditState.getBlurFaceList().get(i))) {
                    enabledRects.add(toRect(faceRects.get(i)));
                }
            }
        }
        return enabledRects;
    }

    /**
     * Scale the preview coordinate rects to the full resolution bitmap coordinate,
     * the result rects are clamped to the bitmap bounds
     * (YuNet boxes can touch the edges, Bitmap.createBitmap throws otherwise)
     */
    private ArrayList<Rect> scaleRectsToFull(ArrayList<Rect> rects, ImageEditState imageEditState,
                                             int fullWidth, int fullHeight) {
        float scaleX = imageEditState.mPreviewWidth > 0
                ? fullWidth / (float) imageEditState.mPreviewWidth : 1f;
        float scaleY = imageEditState.mPreviewHeight > 0
                ? fullHeight / (float) imageEditState.mPreviewHeight : 1f;
        ArrayList<Rect> fullRects = new ArrayList<>();
        for (Rect rect : rects) {
            Rect scaledRect = new Rect(
                    Math.round(rect.left * scaleX),
                    Math.round(rect.top * scaleY),
                    Math.round(rect.right * scaleX),
                    Math.round(rect.bottom * scaleY));
            fullRects.add(clampRect(scaledRect, fullWidth, fullHeight));
        }
        return fullRects;
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
                    .setTitle(R.string.title_blur_face)
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

    private void recyclePreviewCache() {
        if (mPreviewCache != null) {
            recyclePreviewCache(mPreviewCache);
            mPreviewCache = null;
        }
    }

    private void recyclePreviewCache(PreviewCache previewCache) {
        if (previewCache.mPreview != null) {
            previewCache.mPreview.recycle();
        }
        for (Bitmap blurredCrop : previewCache.mBlurredCrops) {
            blurredCrop.recycle();
        }
        previewCache.mBlurredCrops.clear();
    }

    private static Rect toRect(int[] rectArr) {
        return new Rect(rectArr[0], rectArr[1], rectArr[2], rectArr[3]);
    }

    /**
     * Clamp the given rect to the bitmap bounds,
     * a touching edge is kept at least 1 px inside so the crop never throws
     */
    private static Rect clampRect(Rect rect, int width, int height) {
        int left = Math.max(0, Math.min(rect.left, width - 1));
        int top = Math.max(0, Math.min(rect.top, height - 1));
        int right = Math.max(left + 1, Math.min(rect.right, width));
        int bottom = Math.max(top + 1, Math.min(rect.bottom, height));
        return new Rect(left, top, right, bottom);
    }
}
