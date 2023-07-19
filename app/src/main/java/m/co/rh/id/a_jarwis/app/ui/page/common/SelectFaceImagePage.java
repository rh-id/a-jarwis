package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.imageitem.ImageListAdapter;
import m.co.rh.id.a_jarwis.app.ui.imageitem.model.ImageItem;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.FileList;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.util.BitmapUtils;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnActivityResult;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

public class SelectFaceImagePage extends StatefulView<Activity> implements NavOnActivityResult<Activity>, RequireComponent<Provider>, View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = "SelectFaceImagePage";

    private static final int REQUEST_CODE_PICK_IMAGE = 1;

    @NavInject
    private transient INavigator mNavigator;
    private transient Provider mSvProvider;
    private transient ExecutorService mExecutorService;
    private transient ILogger mLogger;
    private transient FileHelper mFileHelper;
    private transient RxDisposer mRxDisposer;
    private transient FaceEngine mFaceEngine;

    private transient ImageListAdapter mImageListAdapter;

    private SerialBehaviorSubject<File> mImageViewFile;

    private SerialBehaviorSubject<ArrayList<ImageItem>> mImageItemList;
    private SerialBehaviorSubject<Boolean> mIsLoading;

    public SelectFaceImagePage() {
        mImageViewFile = new SerialBehaviorSubject<>();
        mImageItemList = new SerialBehaviorSubject<>(new ArrayList<>());
        mIsLoading = new SerialBehaviorSubject<>(false);
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mFileHelper = mSvProvider.get(FileHelper.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mFaceEngine = mSvProvider.get(FaceEngine.class);
        if (mImageListAdapter != null) {
            mImageListAdapter.dispose(mNavigator.getActivity());
            mImageListAdapter = null;
        }
        mImageListAdapter = new ImageListAdapter(mNavigator, this);
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_select_face_image, container, false);
        View noRecordView = rootView.findViewById(R.id.text_no_face);
        View containerProgressBar = rootView.findViewById(R.id.container_progressBar);
        RecyclerView listView = rootView.findViewById(R.id.listView);
        listView.setAdapter(mImageListAdapter);
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(activity,
                DividerItemDecoration.VERTICAL);
        listView.addItemDecoration(dividerItemDecoration);
        ImageView imageView = rootView.findViewById(R.id.imageView);
        imageView.setOnClickListener(this);
        Button backButton = rootView.findViewById(R.id.button_back);
        backButton.setOnClickListener(this);
        Button nextButton = rootView.findViewById(R.id.button_next);
        nextButton.setOnClickListener(this);
        mRxDisposer.add("createView_onImageViewChanged", mImageViewFile.getSubject()
                .map(file -> {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        return BitmapFactory.decodeStream(fis);
                    }
                })
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bitmap -> {
                    imageView.setImageBitmap(bitmap);
                    imageView.setOnLongClickListener(this);
                })
        );
        mRxDisposer.add("createView_onImageItemListChanged", mImageItemList.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(imageItems -> {
                            mImageListAdapter.submitList(imageItems);
                            boolean isEmpty = imageItems.isEmpty();
                            nextButton.setEnabled(!isEmpty);
                            if (isEmpty) {
                                noRecordView.setVisibility(View.VISIBLE);
                            } else {
                                noRecordView.setVisibility(View.GONE);
                            }
                        }
                ));
        mRxDisposer.add("createView_onLoading", mIsLoading.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aBoolean -> {
                    if (aBoolean) {
                        rootView.setEnabled(false);
                        containerProgressBar.setVisibility(View.VISIBLE);
                    } else {
                        rootView.setEnabled(true);
                        containerProgressBar.setVisibility(View.GONE);
                    }
                })
        );
        return rootView;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        if (mImageListAdapter != null) {
            mImageListAdapter.dispose(activity);
            mImageListAdapter = null;
        }
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
    }

    @Override
    public void onClick(View view) {
        Activity activity = mNavigator.getActivity();
        int id = view.getId();
        if (id == R.id.button_back) {
            view.setEnabled(false);
            mNavigator.pop();
        } else if (id == R.id.button_next) {
            mIsLoading.onNext(true);
            boolean faceSelected = false;
            ArrayList<ImageItem> imageItems = mImageItemList.getValue();
            for (ImageItem imageItem : imageItems) {
                if (imageItem.isSelected()) {
                    faceSelected = true;
                    break;
                }
            }
            if (!faceSelected) {
                mLogger.i(TAG, mNavigator.getActivity().getString(m.co.rh.id.a_jarwis.base.R.string.no_face_selected));
                mIsLoading.onNext(false);
            } else {
                mRxDisposer.add("onClick_next",
                        Single.fromCallable(() -> {
                                            ArrayList<File> selectedFile = new ArrayList<>();
                                            if (!imageItems.isEmpty()) {
                                                int size = imageItems.size();
                                                for (int i = 0; i < size; i++) {
                                                    ImageItem imageItem = imageItems.get(i);
                                                    if (imageItem.isSelected()) {
                                                        File file = mFileHelper.createTempFile("face_" + i + ".jpg");
                                                        Bitmap bitmap = imageItem.getImage();
                                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(file));
                                                        selectedFile.add(file);
                                                    }
                                                    imageItem.getImage().recycle();
                                                }
                                            }
                                            return selectedFile;
                                        }
                                )
                                .subscribeOn(Schedulers.from(mExecutorService))
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe((files, throwable) -> {
                                    if (throwable != null) {
                                        mLogger.e(TAG, throwable.getMessage(), throwable);
                                        mIsLoading.onNext(false);
                                    } else {
                                        mNavigator.pop(new FileList(files));
                                    }
                                }));
            }
        } else if (id == R.id.imageView) {
            pickImage(activity);
        }
    }

    @Override
    public void onActivityResult(View currentView, Activity activity, INavigator INavigator, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            mIsLoading.onNext(true);
            Uri fullPhotoUri = data.getData();
            mRxDisposer.add("onActivityResult_pickImage",
                    Single.fromCallable(() -> mFileHelper.createImageTempFile(fullPhotoUri))
                            .subscribeOn(Schedulers.from(mExecutorService))
                            .observeOn(Schedulers.from(mExecutorService))
                            .subscribe((file, throwable) -> {
                                if (throwable != null) {
                                    mLogger.e(TAG, throwable.getMessage(), throwable);
                                } else {
                                    Bitmap imageBitmap = BitmapFactory.decodeStream(new FileInputStream(file));
                                    List<Rect> rectList = mFaceEngine.detectFace(imageBitmap);
                                    ArrayList<ImageItem> imageItems = new ArrayList<>();
                                    if (!rectList.isEmpty()) {
                                        for (Rect rect : rectList) {
                                            Bitmap cropFace = BitmapUtils.cropBitmap(imageBitmap, rect);
                                            /* Try to detect the cropped face,
                                             if unable to detect again then it cant be used
                                             */
                                            List<Rect> croppedFaceList = mFaceEngine.detectFace(cropFace);
                                            if (croppedFaceList.size() == 1) {
                                                imageItems.add(new ImageItem(cropFace, true));
                                            }
                                        }
                                    }
                                    mLogger.d(TAG, "totalFace:" + imageItems.size());
                                    mImageItemList.onNext(imageItems);
                                    mImageViewFile.onNext(file);
                                    mIsLoading.onNext(false);
                                }
                            })
            );
        }
    }

    @Override
    public boolean onLongClick(View view) {
        int id = view.getId();
        if (id == R.id.imageView) {
            pickImage(mNavigator.getActivity());
            return true;
        }
        return false;
    }

    private void pickImage(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }
}
