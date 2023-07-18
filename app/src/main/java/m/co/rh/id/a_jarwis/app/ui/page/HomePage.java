package m.co.rh.id.a_jarwis.app.ui.page;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.provider.command.BlurFaceCommand;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.FileList;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.MessageText;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.base.ui.component.AppBarSV;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnActivityResult;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.NavOnRequestPermissionResult;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

public class HomePage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, NavOnActivityResult<Activity>, NavOnRequestPermissionResult<Activity>, DrawerLayout.DrawerListener, View.OnClickListener {
    private static final String TAG = "HomePage";

    private static final int REQUEST_CODE_IMAGE_AUTO_BLUR_FACE = 1;
    private static final int REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE = 2;

    @NavInject
    private transient INavigator mNavigator;
    @NavInject
    private AppBarSV mAppBarSV;
    private boolean mIsDrawerOpen;
    private transient long mLastBackPressMilis;

    // component
    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient BlurFaceCommand mBlurFaceCommand;

    // View related
    private transient DrawerLayout mDrawerLayout;
    private transient View.OnClickListener mOnNavigationClicked;

    private ArrayList<File> mExcludeFacesList;

    public HomePage() {
        mAppBarSV = new AppBarSV();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mBlurFaceCommand = mSvProvider.get(BlurFaceCommand.class);
        mOnNavigationClicked = view -> {
            if (!mDrawerLayout.isOpen()) {
                mDrawerLayout.open();
            }
        };
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootLayout = activity.getLayoutInflater().inflate(R.layout.page_home, container, false);
        View menuSettings = rootLayout.findViewById(R.id.menu_settings);
        menuSettings.setOnClickListener(this);
        View menuDonation = rootLayout.findViewById(R.id.menu_donation);
        menuDonation.setOnClickListener(this);
        mDrawerLayout = rootLayout.findViewById(R.id.drawer);
        mDrawerLayout.addDrawerListener(this);
        mAppBarSV.setTitle(activity.getString(m.co.rh.id.a_jarwis.base.R.string.home));
        mAppBarSV.setNavigationOnClick(mOnNavigationClicked);
        if (mIsDrawerOpen) {
            mDrawerLayout.open();
        }
        Button autoBlurButton = rootLayout.findViewById(R.id.button_auto_blur_face);
        autoBlurButton.setOnClickListener(this);
        Button excludeBlurButton = rootLayout.findViewById(R.id.button_exclude_blur_face);
        excludeBlurButton.setOnClickListener(this);
        ViewGroup containerAppBar = rootLayout.findViewById(R.id.container_app_bar);
        containerAppBar.addView(mAppBarSV.buildView(activity, container));
        return rootLayout;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        mAppBarSV.dispose(activity);
        mAppBarSV = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mDrawerLayout = null;
        mOnNavigationClicked = null;
    }

    @Override
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        if (mDrawerLayout.isOpen()) {
            mDrawerLayout.close();
        } else {
            long currentMilis = System.currentTimeMillis();
            if ((currentMilis - mLastBackPressMilis) < 1000) {
                navigator.finishActivity(null);
            } else {
                mLastBackPressMilis = currentMilis;
                mSvProvider.get(ILogger.class).i(TAG,
                        activity.getString(m.co.rh.id.a_jarwis.base.R.string.toast_back_press_exit));
            }
        }
    }

    @Override
    public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
        // Leave blank
    }

    @Override
    public void onDrawerOpened(@NonNull View drawerView) {
        mIsDrawerOpen = true;
    }

    @Override
    public void onDrawerClosed(@NonNull View drawerView) {
        mIsDrawerOpen = false;
    }

    @Override
    public void onDrawerStateChanged(int newState) {
        // Leave blank
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.menu_settings) {
            mNavigator.push(Routes.SETTINGS_PAGE);
        } else if (id == R.id.menu_donation) {
            mNavigator.push(Routes.DONATIONS_PAGE);
        } else if (id == R.id.button_auto_blur_face) {
            Activity activity = mNavigator.getActivity();
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_IMAGE_AUTO_BLUR_FACE);
            } else {
                pickImage(activity, REQUEST_CODE_IMAGE_AUTO_BLUR_FACE);
            }
        } else if (id == R.id.button_exclude_blur_face) {
            Activity activity = mNavigator.getActivity();
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE);
            } else {
                startExcludeAutoBlur();
            }
        }
    }

    private void startExcludeAutoBlur() {
        int title = m.co.rh.id.a_jarwis.base.R.string.title_what_to_do;
        int body = m.co.rh.id.a_jarwis.base.R.string.pick_image_to_be_excluded_from_blur;
        mNavigator.push(Routes.SHOW_MESSAGE_PAGE, new MessageText(title, body, true)
                , (navigator, navRoute, activity1, currentView) ->
                        startExcludeAutoBlur_processFirstRespond(navRoute));
    }

    private void startExcludeAutoBlur_processFirstRespond(NavRoute navRoute) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof SelectedChoice) {
            int selectedChoice = ((SelectedChoice) serializable).getSelectedChoice();
            if (SelectedChoice.POSITIVE == selectedChoice) {
                mNavigator.push(Routes.SELECT_FACE_IMAGE_PAGE, (navigator, navRoute1, activity1, currentView) ->
                        startExcludeAutoBlur_processSecondRespond(activity1, navRoute1));
            }
        }
    }

    private void startExcludeAutoBlur_processSecondRespond(Activity activity, NavRoute navRoute) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof FileList) {
            ArrayList<File> fileList = ((FileList) serializable).getFiles();
            if (!fileList.isEmpty()) {
                mExcludeFacesList = fileList;
                pickImage(activity, REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE);
            }
        }
    }

    private void pickImage(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(intent, requestCode);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onActivityResult(View currentView, Activity activity, INavigator INavigator, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_IMAGE_AUTO_BLUR_FACE && resultCode == Activity.RESULT_OK) {
            ClipData listPhoto = data.getClipData();
            Uri fullPhotoUri = data.getData();
            BiConsumer<File, Throwable> consumeFile = (file, throwable) -> {
                if (throwable != null) {
                    mLogger.e(TAG, throwable.getMessage(), throwable);
                } else {
                    mLogger.i(TAG, mSvProvider.getContext()
                            .getString(m.co.rh.id.a_jarwis.base.R.string.processing_,
                                    file.getName()));
                }
            };
            if (listPhoto != null) {
                int count = listPhoto.getItemCount();
                List<Uri> uriList = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    uriList.add(listPhoto.getItemAt(i).getUri());
                }
                if (!uriList.isEmpty()) {
                    mRxDisposer.add("onActivityResult_autoBlurFace_multiple",
                            Flowable.fromIterable(uriList)
                                    .map(uri -> mBlurFaceCommand.execute(uri).blockingGet())
                                    .subscribeOn(Schedulers.from(mExecutorService))
                                    .doOnError(throwable -> consumeFile.accept(null, throwable))
                                    .subscribe(file -> consumeFile.accept(file, null))
                    );
                }
            } else {
                mRxDisposer.add("onActivityResult_autoBlurFace",
                        mBlurFaceCommand.execute(fullPhotoUri)
                                .subscribeOn(Schedulers.from(mExecutorService))
                                .subscribe(consumeFile));
            }
        } else if (requestCode == REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE && resultCode == Activity.RESULT_OK) {
            ClipData listPhoto = data.getClipData();
            Uri fullPhotoUri = data.getData();
            BiConsumer<File, Throwable> consumeFile = (file, throwable) -> {
                if (throwable != null) {
                    mLogger.e(TAG, throwable.getMessage(), throwable);
                } else {
                    mLogger.i(TAG, mSvProvider.getContext()
                            .getString(m.co.rh.id.a_jarwis.base.R.string.processing_,
                                    file.getName()));
                }
            };
            if (listPhoto != null) {
                int count = listPhoto.getItemCount();
                List<Uri> uriList = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    uriList.add(listPhoto.getItemAt(i).getUri());
                }
                if (!uriList.isEmpty()) {
                    mRxDisposer.add("onActivityResult_excludeAutoBlurFace_multiple",
                            Flowable.fromIterable(uriList)
                                    .map(uri -> mBlurFaceCommand.execute(uri, mExcludeFacesList).blockingGet())
                                    .subscribeOn(Schedulers.from(mExecutorService))
                                    .doOnError(throwable -> consumeFile.accept(null, throwable))
                                    .subscribe(file -> consumeFile.accept(file, null))
                    );
                }
            } else {
                mRxDisposer.add("onActivityResult_excludeAutoBlurFace",
                        mBlurFaceCommand.execute(fullPhotoUri, mExcludeFacesList)
                                .subscribeOn(Schedulers.from(mExecutorService))
                                .subscribe(consumeFile));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(View currentView, Activity activity, INavigator INavigator, int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_IMAGE_AUTO_BLUR_FACE
                && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pickImage(activity, requestCode);
        } else if (requestCode == REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE
                && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startExcludeAutoBlur();
        }
    }
}