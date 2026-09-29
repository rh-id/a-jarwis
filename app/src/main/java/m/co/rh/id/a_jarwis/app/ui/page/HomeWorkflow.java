package m.co.rh.id.a_jarwis.app.ui.page;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import io.reactivex.rxjava3.core.Single;
import m.co.rh.id.a_jarwis.app.provider.command.BlurFaceCommand;
import m.co.rh.id.a_jarwis.app.provider.command.STApplyCommand;
import m.co.rh.id.a_jarwis.app.ui.page.common.ModelDownloadDialog;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.FileList;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.MessageText;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedTheme;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.component.INavigator;

class HomeWorkflow implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int REQUEST_CODE_IMAGE_AUTO_BLUR_FACE = 1;
    private static final int REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE = 2;
    private static final int REQUEST_CODE_IMAGE_SELECTIVE_BLUR_FACE = 3;
    private static final int REQUEST_CODE_IMAGE_NST_APPLY_PICTURE = 4;

    private ArrayList<File> mFacesList;
    private SelectedTheme mSelectedNSTTheme;

    private transient INavigator mNavigator;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient BlurFaceCommand mBlurFaceCommand;
    private transient STApplyCommand mSTApplyCommand;
    private transient HomeImageProcessor mImageProcessor;

    private interface ContinueAction extends Serializable {
        void run();
    }

    void init(INavigator navigator, IStatefulViewProvider svProvider) {
        mNavigator = navigator;
        mExecutorService = svProvider.get(ExecutorService.class);
        mRxDisposer = svProvider.get(RxDisposer.class);
        mBlurFaceCommand = svProvider.get(BlurFaceCommand.class);
        mSTApplyCommand = svProvider.get(STApplyCommand.class);
        mImageProcessor = new HomeImageProcessor(svProvider);
    }

    public void startAutoBlur() {
        requestWriteExternalStoragePermission(() -> startIfFaceModelsAvailable(() ->
                pickImage(REQUEST_CODE_IMAGE_AUTO_BLUR_FACE)), REQUEST_CODE_IMAGE_AUTO_BLUR_FACE);
    }

    public void startSelectiveBlur() {
        requestWriteExternalStoragePermission(() -> startFacePickFlow(
                m.co.rh.id.a_jarwis.R.string.pick_image_for_selective_blur,
                REQUEST_CODE_IMAGE_SELECTIVE_BLUR_FACE), REQUEST_CODE_IMAGE_SELECTIVE_BLUR_FACE);
    }

    public void startExcludeBlur() {
        requestWriteExternalStoragePermission(() -> startFacePickFlow(
                m.co.rh.id.a_jarwis.R.string.pick_image_to_be_excluded_from_blur,
                REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE), REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE);
    }

    public void startNstApply() {
        requestWriteExternalStoragePermission(() -> {
            int title = m.co.rh.id.a_jarwis.R.string.title_what_to_do;
            int body = m.co.rh.id.a_jarwis.R.string.pick_image_for_nst_apply_picture;
            mNavigator.push(Routes.SHOW_MESSAGE_PAGE, new MessageText(title, body, true)
                    , (navigator, navRoute, activity1, currentView) ->
                            startNstApply_processFirstRespond(navRoute));
        }, REQUEST_CODE_IMAGE_NST_APPLY_PICTURE);
    }

    private void requestWriteExternalStoragePermission(ContinueAction continueAction, int requestCode) {
        Activity activity = mNavigator.getActivity();
        if (android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                && ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
        } else {
            try {
                continueAction.run();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void startIfFaceModelsAvailable(ContinueAction continueAction) {
        Activity activity = mNavigator.getActivity();
        ArrayList<ModelType> missingModels = new ArrayList<>();
        if (!ModelCatalog.isAvailable(activity, ModelType.FACE_DETECT)) {
            missingModels.add(ModelType.FACE_DETECT);
        }
        if (!ModelCatalog.isAvailable(activity, ModelType.FACE_RECOGNIZER)) {
            missingModels.add(ModelType.FACE_RECOGNIZER);
        }
        if (missingModels.isEmpty()) {
            continueAction.run();
        } else {
            startModelDownload(missingModels, continueAction);
        }
    }

    private void startModelDownload(ArrayList<ModelType> missingModels, ContinueAction continueAction) {
        mNavigator.push(Routes.MODEL_DOWNLOAD_PAGE, new ModelDownloadDialog.ModelList(missingModels),
                (navigator, navRoute, activity1, currentView) -> {
                    Serializable serializable = navRoute.getRouteResult();
                    if (serializable instanceof SelectedChoice
                            && ((SelectedChoice) serializable).getSelectedChoice()
                            == SelectedChoice.POSITIVE) {
                        continueAction.run();
                    }
                });
    }

    private void startFacePickFlow(int bodyResId, int requestCode) {
        startIfFaceModelsAvailable(() -> {
            int title = m.co.rh.id.a_jarwis.R.string.title_what_to_do;
            mNavigator.push(Routes.SHOW_MESSAGE_PAGE, new MessageText(title, bodyResId, true)
                    , (navigator, navRoute, activity1, currentView) ->
                            startFacePickFlow_processFirstRespond(navRoute, requestCode));
        });
    }

    private void startFacePickFlow_processFirstRespond(NavRoute navRoute, int requestCode) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof SelectedChoice) {
            int selectedChoice = ((SelectedChoice) serializable).getSelectedChoice();
            if (SelectedChoice.POSITIVE == selectedChoice) {
                mNavigator.push(Routes.SELECT_FACE_IMAGE_PAGE, (navigator, navRoute1, activity1, currentView) ->
                        startFacePickFlow_processSecondRespond(navRoute1, requestCode));
            }
        }
    }

    private void startFacePickFlow_processSecondRespond(NavRoute navRoute, int requestCode) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof FileList) {
            ArrayList<File> fileList = ((FileList) serializable).getFiles();
            if (!fileList.isEmpty()) {
                mFacesList = fileList;
                pickImage(requestCode);
            }
        }
    }

    private void startNstApply_processFirstRespond(NavRoute navRoute) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof SelectedChoice) {
            int selectedChoice = ((SelectedChoice) serializable).getSelectedChoice();
            if (SelectedChoice.POSITIVE == selectedChoice) {
                mNavigator.push(Routes.SELECT_NST_THEME_PAGE, (navigator, navRoute1, activity1, currentView) ->
                        startNstApply_processSecondRespond(navRoute1));
            }
        }
    }

    private void startNstApply_processSecondRespond(NavRoute navRoute) {
        Serializable serializable = navRoute.getRouteResult();
        if (serializable instanceof SelectedTheme) {
            mSelectedNSTTheme = (SelectedTheme) serializable;
            pickImage(REQUEST_CODE_IMAGE_NST_APPLY_PICTURE);
        }
    }

    private void pickImage(int requestCode) {
        Activity activity = mNavigator.getActivity();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(intent, requestCode);
    }

    public void onPermissionResult(int requestCode, boolean granted) {
        if (!granted) {
            return;
        }
        if (requestCode == REQUEST_CODE_IMAGE_AUTO_BLUR_FACE) {
            startAutoBlur();
        } else if (requestCode == REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE) {
            startExcludeBlur();
        } else if (requestCode == REQUEST_CODE_IMAGE_SELECTIVE_BLUR_FACE) {
            startSelectiveBlur();
        } else if (requestCode == REQUEST_CODE_IMAGE_NST_APPLY_PICTURE) {
            startNstApply();
        }
    }

    public void onImagePicked(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_CODE_IMAGE_AUTO_BLUR_FACE) {
            processPickedImages("onActivityResult_autoBlurFace", data,
                    uri -> mBlurFaceCommand.execute(uri));
        } else if (requestCode == REQUEST_CODE_IMAGE_EXCLUDE_BLUR_FACE) {
            processPickedImages("onActivityResult_excludeAutoBlurFace", data,
                    uri -> mBlurFaceCommand.execute(uri, mFacesList));
        } else if (requestCode == REQUEST_CODE_IMAGE_SELECTIVE_BLUR_FACE) {
            processPickedImages("onActivityResult_selectiveAutoBlurFace", data,
                    uri -> mBlurFaceCommand.execute(uri, mFacesList, false));
        } else if (requestCode == REQUEST_CODE_IMAGE_NST_APPLY_PICTURE) {
            processPickedImages("onActivityResult_nstApplyPicture", data,
                    uri -> mSTApplyCommand.execute(uri, mSelectedNSTTheme.getSelectedThemes()));
        }
    }

    private void processPickedImages(String baseTag, Intent data, Function<Uri, Single<File>> commandFactory) {
        if (data == null) {
            return;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            int count = clipData.getItemCount();
            List<Uri> uriList = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                uriList.add(clipData.getItemAt(i).getUri());
            }
            if (!uriList.isEmpty()) {
                mImageProcessor.processImages(baseTag + "_multiple", uriList, commandFactory);
            }
        } else if (data.getData() != null) {
            mImageProcessor.processImage(baseTag, data.getData(), commandFactory);
        }
    }
}
