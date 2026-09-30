package m.co.rh.id.a_jarwis.app.ui.page.editor;

import m.co.rh.id.a_jarwis.app.ui.page.common.ModelDownloadDialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.UiUtils;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.STEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * Interactive neural style transfer editor.
 * Shows each picked image with a row of style tiles, tapping a style renders
 * a live stylized preview of the image on a downscaled bitmap. The save/share
 * applies the selected style at the full resolution before writing the result
 * to the gallery/temp file.
 */
public class StyleEditorPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, View.OnClickListener {

    private static final String TAG = "StyleEditorPage";

    /**
     * Theme constant meaning no style applied (the original image is shown)
     */
    private static final int THEME_NONE = 0;
    /**
     * Max dimension (longest side) of the decoded preview bitmap shown on the editor
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * Size (in dip) of a style tile thumbnail
     */
    private static final int STYLE_THUMB_DIP = 64;
    /**
     * File name prefix of the temp file created for sharing an edited image
     */
    private static final String SHARE_FILE_PREFIX = "steditor_share_";
    /**
     * File name prefix of the private cache copy of a picked image, created once
     * per image at load time because content uri grants are ephemeral and
     * do not survive a process restart
     */
    private static final String EDITOR_SOURCE_PREFIX = "st_editor_source_";

    /**
     * Serializable editor state of this page,
     * only holds serializable fields (strings, boxed types and primitive arrays)
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
         * Selected neural style transfer theme,
         * {@link #THEME_NONE} means the original image without any style
         */
        private int mSelectedTheme;
        /**
         * Known image dimension (longest-side preview decode basis),
         * zero means the image has not been loaded yet
         */
        private int mPreviewWidth;
        private int mPreviewHeight;
    }

    /**
     * Runtime preview cache of the currently displayed image,
     * never serialized (bitmaps must not be in the navigator snapshot)
     */
    private static class PreviewCache {
        private final int mImageIndex;
        private final int mTheme;
        private final Bitmap mOriginalPreview;
        /**
         * Stylized version of {@link #mOriginalPreview},
         * null when no style is applied or the style model was not downloaded yet
         */
        private final Bitmap mStyledPreview;

        private PreviewCache(int imageIndex, int theme,
                             Bitmap originalPreview, Bitmap styledPreview) {
            mImageIndex = imageIndex;
            mTheme = theme;
            mOriginalPreview = originalPreview;
            mStyledPreview = styledPreview;
        }
    }

    /**
     * Load result of a single image, used while the initial load pipeline is running
     */
    private static class LoadedImage {
        private final int mIndex;
        /**
         * Private cache copy of the source image created during load when
         * the source uri is not a file uri, null when the source is already a
         * file uri or the copy failed (the original uri keeps being used)
         */
        private File mSourceFile;
        private int mPreviewWidth;
        private int mPreviewHeight;

        private LoadedImage(int index) {
            mIndex = index;
        }
    }

    /**
     * Save result of a single image, used while the save pipeline is running
     */
    private static class SaveResult {
        private static final int STATUS_SAVED = 1;
        private static final int STATUS_FAILED = 2;
        private static final int STATUS_NO_STYLE = 3;

        private final int mIndex;
        private int mStatus;

        private SaveResult(int index) {
            mIndex = index;
        }
    }

    /**
     * Runtime holder of a single style tile view built in code
     */
    private static class StyleTile {
        private final int mTheme;
        private final View mRoot;
        private final View mBadge;

        private StyleTile(int theme, View root, View badge) {
            mTheme = theme;
            mRoot = root;
            mBadge = badge;
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
    private transient STEngine mSTEngine;
    private transient FileHelper mFileHelper;
    private transient ImageHelper mImageHelper;
    private transient MediaHelper mMediaHelper;

    // view
    private transient AppCompatImageView mImageView;
    private transient ProgressBar mProgressRender;
    private transient LinearLayout mContainerStyle;
    private transient View mContainerPager;
    private transient AppCompatTextView mTextImagePager;
    private transient AppCompatImageButton mButtonPrevImage;
    private transient AppCompatImageButton mButtonNextImage;
    private transient MaterialButton mButtonApplyAll;
    private transient AlertDialog mProgressDialog;
    private transient ProgressBar mProgressBar;
    private transient AppCompatTextView mTextProgress;

    // runtime
    private transient PreviewCache mPreviewCache;
    private transient boolean mIsRendering;
    private transient boolean mIsLoading;
    private transient int mLoadProgressCount;
    private transient ArrayList<SaveResult> mSaveResults;
    private transient File mLastShareFile;
    private transient ArrayList<StyleTile> mStyleTiles;
    /**
     * Private cache copies of the picked images created by this session,
     * never serialized. Deleted on dispose, a process death leaves them in
     * place so the restored snapshot can still read them (stale leftovers are
     * purged by age in a later session)
     */
    private transient ArrayList<File> mSessionSourceFiles;
    /**
     * Per-session availability cache of the style models (index 0 is unused,
     * THEME_NONE has no model), populated lazily by
     * {@link #isModelAvailable(int)} and refreshed when a model download
     * completes, so the tile updates do not run disk checks on every state
     * emission
     */
    private transient boolean[] mModelAvailabilityCache;

    public StyleEditorPage() {
        mEditorState = new SerialBehaviorSubject<>();
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mAppContext = mSvProvider.getContext().getApplicationContext();
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mSTEngine = mSvProvider.get(STEngine.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mMediaHelper = mSvProvider.get(MediaHelper.class);
        // session-level source copy list (not persisted), re-initialized here
        // because Java deserialization does not run the constructor
        mSessionSourceFiles = new ArrayList<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_style_editor, container, false);
        MaterialToolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        mImageView = rootView.findViewById(R.id.imageView);
        mProgressRender = rootView.findViewById(R.id.progress_render);
        mContainerStyle = rootView.findViewById(R.id.container_style);
        buildStyleTiles(activity);
        mContainerPager = rootView.findViewById(R.id.container_pager);
        mTextImagePager = rootView.findViewById(R.id.text_image_pager);
        mButtonPrevImage = rootView.findViewById(R.id.button_prev_image);
        mButtonPrevImage.setOnClickListener(this);
        mButtonNextImage = rootView.findViewById(R.id.button_next_image);
        mButtonNextImage.setOnClickListener(this);
        mButtonApplyAll = rootView.findViewById(R.id.button_apply_all);
        mButtonApplyAll.setOnClickListener(this);
        View saveButton = rootView.findViewById(R.id.button_save);
        saveButton.setOnClickListener(this);
        View shareButton = rootView.findViewById(R.id.button_share);
        shareButton.setOnClickListener(this);
        mRxDisposer.add("createView_onEditorStateChanged", mEditorState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onEditorStateChanged));
        if (mEditorState.getValue() == null) {
            // first open, parse the picked image uris
            mEditorState.onNext(parseRouteArgs());
        }
        // load the images whose dimension is not known yet (mPreviewWidth == 0),
        // on restore (e.g. process death in the middle of the load) only the pending
        // images are re-loaded, the loaded ones keep their persisted file uris
        // and the subscription above re-decodes their preview without re-loading
        startInitialLoad(mEditorState.getValue());
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
        mProgressRender = null;
        recyclePreviewCache();
        if (mLastShareFile != null) {
            mLastShareFile.delete();
            mLastShareFile = null;
        }
        if (mSessionSourceFiles != null) {
            // best effort deletion of the source copies owned by this session,
            // nothing reads them once the editor page is popped. The list is kept
            // (cleared only) so a straggling load emission can never NPE
            for (File sourceFile : mSessionSourceFiles) {
                sourceFile.delete();
            }
            mSessionSourceFiles.clear();
        }
        mImageView = null;
        mContainerStyle = null;
        if (mStyleTiles != null) {
            mStyleTiles.clear();
            mStyleTiles = null;
        }
        mContainerPager = null;
        mTextImagePager = null;
        mButtonPrevImage = null;
        mButtonNextImage = null;
        mButtonApplyAll = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mAppContext = null;
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mSTEngine = null;
        mFileHelper = null;
        mImageHelper = null;
        mMediaHelper = null;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_apply_all) {
            applyStyleToAll();
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
        updateStyleTiles(editorState);
        updatePager(activity, editorState);
        if (mIsLoading) {
            // the load pipeline replaces the source uris of the displayed images,
            // the preview display is blocked until it is done
            return;
        }
        displayCurrentImage(editorState);
    }

    /**
     * Build the style tiles of the bottom style strip in code,
     * tile 0 is the original image without any style
     */
    private void buildStyleTiles(Activity activity) {
        int[] themes = collectStyleThemes();
        mStyleTiles = new ArrayList<>(themes.length);
        int tileSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                STYLE_THUMB_DIP, activity.getResources().getDisplayMetrics()));
        int tileMargin = activity.getResources()
                .getDimensionPixelSize(m.co.rh.id.a_jarwis.base.R.dimen.text_margin) / 2;
        for (int theme : themes) {
            mStyleTiles.add(buildStyleTile(activity, theme, tileSize, tileMargin));
        }
    }

    private StyleTile buildStyleTile(Activity activity, int theme, int tileSize, int tileMargin) {
        FrameLayout tileRoot = new FrameLayout(activity);
        tileRoot.setBackgroundResource(R.drawable.state_selected);
        tileRoot.setClickable(true);
        tileRoot.setFocusable(true);
        int tilePadding = tileMargin / 2;
        tileRoot.setPadding(tilePadding, tilePadding, tilePadding, tilePadding);
        LinearLayout tileContent = new LinearLayout(activity);
        tileContent.setOrientation(LinearLayout.VERTICAL);
        tileContent.setGravity(Gravity.CENTER);
        if (theme == THEME_NONE) {
            // simple placeholder in place of the style thumbnail
            View placeholder = new View(activity);
            placeholder.setBackgroundResource(R.color.transparent_gray_700);
            tileContent.addView(placeholder, new LinearLayout.LayoutParams(tileSize, tileSize));
        } else {
            AppCompatImageView thumb = new AppCompatImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageResource(themeThumbRes(theme));
            tileContent.addView(thumb, new LinearLayout.LayoutParams(tileSize, tileSize));
        }
        AppCompatTextView label = new AppCompatTextView(activity);
        label.setBackgroundResource(R.color.transparent_gray_700);
        label.setTextColor(ContextCompat.getColor(activity,
                m.co.rh.id.a_jarwis.base.R.color.white));
        label.setTextSize(14);
        label.setPadding(tilePadding, 0, tilePadding, 0);
        label.setText(themeLabelRes(theme));
        tileContent.addView(label);
        tileRoot.addView(tileContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AppCompatTextView badge = new AppCompatTextView(activity);
        badge.setBackgroundResource(m.co.rh.id.a_jarwis.base.R.color.red_800);
        badge.setTextColor(ContextCompat.getColor(activity,
                m.co.rh.id.a_jarwis.base.R.color.white));
        badge.setTextSize(12);
        int badgePadding = tilePadding / 2;
        badge.setPadding(badgePadding, badgePadding, badgePadding, badgePadding);
        // down arrow glyph so the badge is not an empty red box
        badge.setText("\u2193");
        badge.setContentDescription(activity.getString(R.string.badge_content_description));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.setMargins(badgePadding, badgePadding, badgePadding, 0);
        badge.setVisibility(View.GONE);
        tileRoot.addView(badge, badgeParams);
        tileRoot.setOnClickListener(v -> selectStyle(theme));
        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.setMargins(tileMargin, 0, tileMargin, 0);
        mContainerStyle.addView(tileRoot, rootParams);
        return new StyleTile(theme, tileRoot, badge);
    }

    /**
     * Reflect the style of the currently displayed image in the tiles
     * (selection highlight and the model download badge of each styled tile)
     */
    private void updateStyleTiles(EditorState editorState) {
        if (mStyleTiles == null) {
            return;
        }
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        for (StyleTile styleTile : mStyleTiles) {
            styleTile.mRoot.setSelected(styleTile.mTheme == imageEditState.mSelectedTheme);
            if (styleTile.mTheme != THEME_NONE) {
                boolean available = isModelAvailable(styleTile.mTheme);
                styleTile.mBadge.setVisibility(available ? View.GONE : View.VISIBLE);
            }
        }
        if (mButtonApplyAll != null) {
            // applying THEME_NONE to all would silently clear the style of every
            // other image, so the bulk action is offered only with a real style
            boolean hasStyle = imageEditState.mSelectedTheme != THEME_NONE;
            mButtonApplyAll.setEnabled(hasStyle);
            mButtonApplyAll.setAlpha(hasStyle ? 1.0f : 0.5f);
        }
    }

    /**
     * @return true when the style model of the given theme is downloaded, served
     * from the per-session cache populated on first use
     * ({@code theme} must not be {@link #THEME_NONE})
     */
    private boolean isModelAvailable(int theme) {
        if (mModelAvailabilityCache == null) {
            mModelAvailabilityCache = new boolean[6];
            // single population pass, the later reads are pure memory reads
            for (int i = 1; i < mModelAvailabilityCache.length; i++) {
                mModelAvailabilityCache[i] = ModelCatalog.isAvailable(mAppContext,
                        ModelCatalog.fromTheme(i));
            }
        }
        return mModelAvailabilityCache[theme];
    }

    /**
     * Refresh the per-session model availability cache after a model download,
     * the state re-emission that follows reflects it in the tile badges
     */
    private void refreshModelAvailabilityCache() {
        if (mModelAvailabilityCache == null) {
            // never read yet, the first isModelAvailable call populates it fresh
            return;
        }
        for (int i = 1; i < mModelAvailabilityCache.length; i++) {
            mModelAvailabilityCache[i] = ModelCatalog.isAvailable(mAppContext,
                    ModelCatalog.fromTheme(i));
        }
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
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (isCacheMatch(mPreviewCache, editorState.mCurrentIndex, imageEditState.mSelectedTheme)) {
            showPreviewCache(editorState);
        } else if (mIsRendering) {
            // a render is already in flight, its completion re-evaluates the display
            return;
        } else {
            preparePreviewCache(editorState);
        }
    }

    /**
     * @return true when the given preview cache can be reused as is for the
     * image at the given index with the given theme (rebuild/trigger duty,
     * decides whether a render must be scheduled, see showPreviewCache for the
     * display duty). A styled cache whose styled bitmap is missing (the style
     * model was not downloaded at render time) never matches so the render is
     * re-triggered once the model is available
     */
    private boolean isCacheMatch(PreviewCache previewCache, int index, int theme) {
        return previewCache != null
                && previewCache.mImageIndex == index
                && previewCache.mTheme == theme
                && (theme == THEME_NONE || previewCache.mStyledPreview != null);
    }

    /**
     * Load the images whose dimension is not known yet,
     * an image is pending when its dimension has never been recorded
     */
    private void startInitialLoad(EditorState editorState) {
        ArrayList<Integer> pendingIndexes = new ArrayList<>();
        for (int i = 0; i < editorState.mUris.size(); i++) {
            if (editorState.mImageEditStates.get(i).mPreviewWidth == 0) {
                pendingIndexes.add(i);
            }
        }
        if (pendingIndexes.isEmpty()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        int total = pendingIndexes.size();
        mIsLoading = true;
        mLoadProgressCount = 0;
        showProgressDialog(true, total,
                activity.getString(R.string.loading_images_progress,
                        mLoadProgressCount, total));
        mRxDisposer.add("startInitialLoad", Flowable.fromIterable(pendingIndexes)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> loadImageTask(editorState, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        loadedImage -> {
                            onImageLoaded(editorState, loadedImage);
                            mLoadProgressCount++;
                            updateProgressDialog(mLoadProgressCount, total,
                                    activity.getString(R.string.loading_images_progress,
                                            mLoadProgressCount, total));
                        },
                        throwable -> {
                            mIsLoading = false;
                            hideProgressDialog();
                            // a catastrophic scheduler error must not leave a blank editor,
                            // the preview build degrades gracefully to the original image
                            displayCurrentImage(mEditorState.getValue());
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            mIsLoading = false;
                            hideProgressDialog();
                            displayCurrentImage(mEditorState.getValue());
                        }
                ));
    }

    /**
     * Copy the source image at the given index to a private cache file when it is
     * not a file uri and read its dimension. Runs on a background thread.
     */
    private LoadedImage loadImageTask(EditorState editorState, int index) {
        LoadedImage loadedImage = new LoadedImage(index);
        try {
            Uri sourceUri = Uri.parse(editorState.mUris.get(index));
            // content uri grants are ephemeral and die with the process, copy the
            // image to a private cache file once so the editor still works after a
            // process restart (the snapshot persists the file uri, see onImageLoaded)
            if (!"file".equals(sourceUri.getScheme())) {
                loadedImage.mSourceFile = copySourceToTempFile(sourceUri, index);
            }
            Uri uri = loadedImage.mSourceFile != null
                    ? Uri.fromFile(loadedImage.mSourceFile) : sourceUri;
            int[] dimension = mImageHelper.decodeImageDimension(uri);
            loadedImage.mPreviewWidth = dimension[0];
            loadedImage.mPreviewHeight = dimension[1];
        } catch (Exception | OutOfMemoryError e) {
            // the image stays pending (dimension not recorded) and keeps the
            // editor usable for the remaining images
            mLogger.e(TAG, e.getMessage(), e);
        }
        return loadedImage;
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

    private void onImageLoaded(EditorState editorState, LoadedImage loadedImage) {
        ImageEditState imageEditState = editorState.mImageEditStates.get(loadedImage.mIndex);
        if (loadedImage.mPreviewWidth > 0) {
            imageEditState.mPreviewWidth = loadedImage.mPreviewWidth;
            imageEditState.mPreviewHeight = loadedImage.mPreviewHeight;
        }
        if (loadedImage.mSourceFile != null) {
            // replace the ephemeral content uri with the private copy uri on the
            // main thread before the state emission, so the snapshot persists the
            // file uri and every later step (preview render, save, share, restore)
            // reads the copy
            editorState.mUris.set(loadedImage.mIndex,
                    Uri.fromFile(loadedImage.mSourceFile).toString());
            if (mSessionSourceFiles == null) {
                // belt-and-braces against a deserialized instance (the list is
                // normally re-initialized in provideComponent)
                mSessionSourceFiles = new ArrayList<>();
            }
            mSessionSourceFiles.add(loadedImage.mSourceFile);
        }
        mEditorState.onNext(editorState);
    }

    /**
     * Decode the preview of the current image and apply its selected style,
     * shown only on completion through the stale guarded subscriber
     */
    private void preparePreviewCache(EditorState editorState) {
        int index = editorState.mCurrentIndex;
        int theme = editorState.mImageEditStates.get(index).mSelectedTheme;
        mIsRendering = true;
        mProgressRender.setVisibility(View.VISIBLE);
        mRxDisposer.add("renderCurrentImage", Single.fromCallable(() ->
                        buildPreviewCache(editorState.mUris.get(index), index, theme))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((previewCache, throwable) -> {
                    mIsRendering = false;
                    mProgressRender.setVisibility(View.GONE);
                    if (throwable != null) {
                        // the previous image stays shown so the editor keeps usable
                        mLogger.e(TAG, throwable.getMessage(), throwable);
                        return;
                    }
                    EditorState currentState = mEditorState.getValue();
                    ImageEditState currentStateEdit =
                            currentState.mImageEditStates.get(currentState.mCurrentIndex);
                    if (previewCache.mImageIndex != currentState.mCurrentIndex
                            || previewCache.mTheme != currentStateEdit.mSelectedTheme) {
                        // the user has switched to another image or style meanwhile
                        recyclePreviewCache(previewCache);
                        return;
                    }
                    recyclePreviewCache();
                    mPreviewCache = previewCache;
                    recordPreviewSize(currentStateEdit, previewCache);
                    showPreviewCache(mEditorState.getValue());
                    if (previewCache.mStyledPreview == null
                            && previewCache.mTheme != THEME_NONE
                            && ModelCatalog.isAvailable(mAppContext,
                            ModelCatalog.fromTheme(previewCache.mTheme))) {
                        // the model finished downloading while the render was in
                        // flight and the re-emission that would have re-triggered
                        // the render was swallowed, schedule exactly one re-render
                        // (its styled bitmap terminates the cycle, the disposer
                        // key below replaces any duplicate)
                        preparePreviewCache(mEditorState.getValue());
                    }
                }));
    }

    /**
     * Decode the downscaled preview of the given image and apply the given theme
     * when its model is available. Runs on a background thread.
     */
    private PreviewCache buildPreviewCache(String uriString, int index, int theme) throws IOException {
        Uri uri = Uri.parse(uriString);
        Bitmap preview = mImageHelper.decodeDownscaledBitmap(uri, PREVIEW_MAX_DIM);
        try {
            Bitmap styledPreview = null;
            if (theme != THEME_NONE
                    && ModelCatalog.isAvailable(mAppContext, ModelCatalog.fromTheme(theme))) {
                styledPreview = mSTEngine.apply(preview, theme);
            }
            return new PreviewCache(index, theme, preview, styledPreview);
        } catch (Exception | OutOfMemoryError e) {
            // the preview cache was never created, recycle the preview before
            // rethrowing so the failure does not leak it
            preview.recycle();
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError) instead of
            // delivering them to the error consumer, wrap it into a runtime exception
            // so it is handled as a render failure and the editor stays usable
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Show the preview of the currently displayed image.
     * Unlike {@link #isCacheMatch(PreviewCache, int, int)} (the rebuild/trigger
     * duty) this display duty never bails on a missing styled bitmap: the styled
     * preview is shown when it exists, otherwise the original preview is shown,
     * so the view always ends up attached to a live bitmap of the current cache
     * (a recycled bitmap left attached here would crash the next draw).
     * Runs on the main thread, only bitmap drawing happens here so the
     * bitmap lifetime is never shared with the background threads.
     */
    private void showPreviewCache(EditorState editorState) {
        PreviewCache previewCache = mPreviewCache;
        if (previewCache == null
                || previewCache.mImageIndex != editorState.mCurrentIndex
                || previewCache.mTheme != editorState.mImageEditStates
                .get(editorState.mCurrentIndex).mSelectedTheme) {
            return;
        }
        Bitmap bitmapToShow = previewCache.mTheme != THEME_NONE
                && previewCache.mStyledPreview != null
                ? previewCache.mStyledPreview : previewCache.mOriginalPreview;
        mImageView.setImageBitmap(bitmapToShow);
        mImageView.invalidate();
    }

    private void recordPreviewSize(ImageEditState imageEditState, PreviewCache previewCache) {
        int previewWidth = previewCache.mOriginalPreview.getWidth();
        int previewHeight = previewCache.mOriginalPreview.getHeight();
        if (imageEditState.mPreviewWidth != previewWidth
                || imageEditState.mPreviewHeight != previewHeight) {
            imageEditState.mPreviewWidth = previewWidth;
            imageEditState.mPreviewHeight = previewHeight;
        }
    }

    /**
     * Apply the given theme to the currently displayed image,
     * the state is written first so the emission triggers the render
     * of the new theme (and the tile highlight update)
     */
    private void selectStyle(int theme) {
        if (mIsRendering || isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        ImageEditState imageEditState = editorState.mImageEditStates.get(editorState.mCurrentIndex);
        if (imageEditState.mSelectedTheme == theme) {
            return;
        }
        imageEditState.mSelectedTheme = theme;
        editorState.mHasUnsavedChanges = true;
        mEditorState.onNext(editorState);
        if (theme != THEME_NONE && !isModelAvailable(theme)) {
            startModelDownload(theme);
        }
    }

    /**
     * Prompt the download of the missing model of the given theme,
     * on success the current state is re-emitted to re-trigger the render
     */
    private void startModelDownload(int theme) {
        ArrayList<ModelType> models = new ArrayList<>(
                Arrays.asList(ModelCatalog.fromTheme(theme)));
        mNavigator.push(Routes.MODEL_DOWNLOAD_PAGE, new ModelDownloadDialog.ModelList(models),
                (navigator, navRoute, activity, currentView) -> {
                    Serializable serializable = navRoute.getRouteResult();
                    if (serializable instanceof SelectedChoice
                            && ((SelectedChoice) serializable).getSelectedChoice()
                            == SelectedChoice.POSITIVE) {
                        // the model may just have been downloaded, refresh the
                        // availability cache before the re-emission updates the tiles
                        refreshModelAvailabilityCache();
                        EditorState editorState = mEditorState.getValue();
                        if (editorState != null) {
                            mEditorState.onNext(editorState);
                        }
                    }
                });
    }

    /**
     * Apply the style of the currently displayed image to every image in the batch.
     * Only reachable with a real style selected because the apply-to-all button is
     * disabled (see {@link #updateStyleTiles}) while the original tile is selected:
     * applying {@link #THEME_NONE} to all would silently clear every image's style.
     */
    private void applyStyleToAll() {
        if (mIsRendering || isWorking()) {
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        int theme = editorState.mImageEditStates.get(editorState.mCurrentIndex).mSelectedTheme;
        boolean changed = false;
        for (ImageEditState imageEditState : editorState.mImageEditStates) {
            if (imageEditState.mSelectedTheme != theme) {
                imageEditState.mSelectedTheme = theme;
                changed = true;
            }
        }
        if (changed) {
            editorState.mHasUnsavedChanges = true;
            mEditorState.onNext(editorState);
        }
    }

    private void switchImage(int direction) {
        if (mIsRendering || isWorking()) {
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

    private void handleBack() {
        if (mIsRendering || isWorking()) {
            // a render/load/save/share is running, the back press is ignored
            return;
        }
        EditorState editorState = mEditorState.getValue();
        if (editorState != null && editorState.mHasUnsavedChanges) {
            Activity activity = mNavigator.getActivity();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_discard_changes)
                    .setMessage(R.string.msg_discard_style_changes)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mNavigator.pop())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            mNavigator.pop();
        }
    }

    private void saveImages() {
        if (mIsRendering || isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
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
     * Save the image at the given index to the gallery when it has a style applied.
     * Runs on a background thread.
     */
    @SuppressLint("MissingPermission")
    private SaveResult saveImageTask(EditorState editorState, int index) {
        SaveResult saveResult = new SaveResult(index);
        try {
            ImageEditState imageEditState = editorState.mImageEditStates.get(index);
            int theme = imageEditState.mSelectedTheme;
            if (theme == THEME_NONE) {
                saveResult.mStatus = SaveResult.STATUS_NO_STYLE;
                return saveResult;
            }
            Uri uri = Uri.parse(editorState.mUris.get(index));
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap styledBitmap = null;
            try {
                styledBitmap = mSTEngine.apply(fullBitmap, theme);
                String title = buildSavedImageTitle(index, theme);
                String stringUrl = mMediaHelper.insertImage(styledBitmap, title, title);
                if (stringUrl == null) {
                    saveResult.mStatus = SaveResult.STATUS_FAILED;
                } else {
                    saveResult.mStatus = SaveResult.STATUS_SAVED;
                }
            } finally {
                fullBitmap.recycle();
                if (styledBitmap != null) {
                    styledBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            saveResult.mStatus = SaveResult.STATUS_FAILED;
        }
        return saveResult;
    }

    private String buildSavedImageTitle(int index, int theme) {
        String token;
        switch (theme) {
            case STEngine.THEME_MOSAIC:
                token = "mosaic";
                break;
            case STEngine.THEME_CANDY:
                token = "candy";
                break;
            case STEngine.THEME_RAIN_PRINCESS:
                token = "rain_princess";
                break;
            case STEngine.THEME_UDNIE:
                token = "udnie";
                break;
            case STEngine.THEME_POINTILISM:
                token = "pointilism";
                break;
            default:
                token = "unknown";
                break;
        }
        return "nst_" + token + "_" + (index + 1) + "_" + System.currentTimeMillis() + ".jpg";
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
            } else if (saveResult.mStatus == SaveResult.STATUS_NO_STYLE) {
                message.append("\n").append(activity.getString(R.string.no_style_applied_, imageLabel));
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
        if (mIsRendering || isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        EditorState editorState = mEditorState.getValue();
        if (editorState == null) {
            return;
        }
        if (editorState.mImageEditStates.get(editorState.mCurrentIndex).mSelectedTheme
                == THEME_NONE) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_share_no_style)
                    .setMessage(R.string.msg_share_no_style)
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
     * Apply the style of the currently displayed image at the full resolution
     * and write it to a temp JPEG file to be shared, the original image is
     * shared when it has no style applied. Runs on a background thread.
     */
    private File buildShareFile(EditorState editorState, int index) {
        Uri uri = Uri.parse(editorState.mUris.get(index));
        int theme = editorState.mImageEditStates.get(index).mSelectedTheme;
        try {
            Bitmap fullBitmap = mImageHelper.decodeFullBitmap(uri);
            Bitmap styledBitmap = null;
            try {
                Bitmap bitmapToSave = fullBitmap;
                if (theme != THEME_NONE) {
                    styledBitmap = mSTEngine.apply(fullBitmap, theme);
                    bitmapToSave = styledBitmap;
                }
                File outFile = mImageHelper.createImageTempFile(
                        SHARE_FILE_PREFIX + System.currentTimeMillis() + ".jpg", bitmapToSave);
                return outFile;
            } finally {
                fullBitmap.recycle();
                if (styledBitmap != null) {
                    styledBitmap.recycle();
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            // RxJava rethrows fatal throwables (e.g. OutOfMemoryError) instead of
            // delivering them to the error consumer, wrap it into a runtime exception
            // so it is handled as a share failure
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static int[] collectStyleThemes() {
        return new int[]{
                THEME_NONE,
                STEngine.THEME_MOSAIC,
                STEngine.THEME_CANDY,
                STEngine.THEME_RAIN_PRINCESS,
                STEngine.THEME_UDNIE,
                STEngine.THEME_POINTILISM
        };
    }

    private static int themeThumbRes(int theme) {
        switch (theme) {
            case STEngine.THEME_MOSAIC:
                return R.drawable.nst_theme_mosaic;
            case STEngine.THEME_CANDY:
                return R.drawable.nst_theme_candy;
            case STEngine.THEME_RAIN_PRINCESS:
                return R.drawable.nst_theme_rain_princess;
            case STEngine.THEME_UDNIE:
                return R.drawable.nst_theme_udnie;
            case STEngine.THEME_POINTILISM:
                return R.drawable.nst_theme_pointilism;
            default:
                throw new IllegalArgumentException("Unknown theme: " + theme);
        }
    }

    private static int themeLabelRes(int theme) {
        switch (theme) {
            case THEME_NONE:
                return R.string.style_original;
            case STEngine.THEME_MOSAIC:
                return R.string.title_mosaic;
            case STEngine.THEME_CANDY:
                return R.string.title_candy;
            case STEngine.THEME_RAIN_PRINCESS:
                return R.string.title_rain_princess;
            case STEngine.THEME_UDNIE:
                return R.string.title_udnie;
            case STEngine.THEME_POINTILISM:
                return R.string.title_pointilism;
            default:
                throw new IllegalArgumentException("Unknown theme: " + theme);
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
                    .setTitle(R.string.title_nst_apply_image)
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
        if (previewCache.mOriginalPreview != null) {
            previewCache.mOriginalPreview.recycle();
        }
        if (previewCache.mStyledPreview != null) {
            previewCache.mStyledPreview.recycle();
        }
    }
}
