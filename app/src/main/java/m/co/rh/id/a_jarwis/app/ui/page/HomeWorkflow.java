package m.co.rh.id.a_jarwis.app.ui.page;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.app.ui.page.common.ModelDownloadDialog;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.anavigator.component.INavigator;

class HomeWorkflow implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int REQUEST_CODE_IMAGE_BLUR_FACE = 1;
    private static final int REQUEST_CODE_IMAGE_NST_APPLY_PICTURE = 4;
    private static final int REQUEST_CODE_IMAGE_QR_CENSOR = 5;
    private static final int REQUEST_CODE_QR_SCAN = 6;
    private static final int REQUEST_CODE_IMAGE_PORTRAIT = 7;

    private transient INavigator mNavigator;

    private interface ContinueAction extends Serializable {
        void run();
    }

    void init(INavigator navigator, IStatefulViewProvider svProvider) {
        mNavigator = navigator;
    }

    public void startBlurFace() {
        requestWriteExternalStoragePermission(() -> startIfFaceModelsAvailable(() ->
                pickImage(REQUEST_CODE_IMAGE_BLUR_FACE)), REQUEST_CODE_IMAGE_BLUR_FACE);
    }

    public void startNstApply() {
        requestWriteExternalStoragePermission(() ->
                pickImage(REQUEST_CODE_IMAGE_NST_APPLY_PICTURE), REQUEST_CODE_IMAGE_NST_APPLY_PICTURE);
    }

    public void startQrCensor() {
        // the QR detection runs fully on the in-box OpenCV detector,
        // no model download is needed
        requestWriteExternalStoragePermission(() ->
                pickImage(REQUEST_CODE_IMAGE_QR_CENSOR), REQUEST_CODE_IMAGE_QR_CENSOR);
    }

    public void startPortrait() {
        requestWriteExternalStoragePermission(() -> startIfPortraitModelsAvailable(() ->
                pickImage(REQUEST_CODE_IMAGE_PORTRAIT)), REQUEST_CODE_IMAGE_PORTRAIT);
    }

    public void startQrScan() {
        // the scanner page opens the live camera directly, the camera permission
        // must be granted before the page is pushed (the scan from image action
        // lives inside the scanner page)
        requestCameraPermission(this::pushQrScanner, REQUEST_CODE_QR_SCAN);
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

    private void requestCameraPermission(ContinueAction continueAction, int requestCode) {
        Activity activity = mNavigator.getActivity();
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, requestCode);
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
        if (missingModels.isEmpty()) {
            continueAction.run();
        } else {
            startModelDownload(missingModels, continueAction);
        }
    }

    private void startIfPortraitModelsAvailable(ContinueAction continueAction) {
        Activity activity = mNavigator.getActivity();
        ArrayList<ModelType> missingModels = new ArrayList<>();
        if (!ModelCatalog.isAvailable(activity, ModelType.PERSON_SEG)) {
            missingModels.add(ModelType.PERSON_SEG);
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
        if (requestCode == REQUEST_CODE_IMAGE_BLUR_FACE) {
            startBlurFace();
        } else if (requestCode == REQUEST_CODE_IMAGE_NST_APPLY_PICTURE) {
            startNstApply();
        } else if (requestCode == REQUEST_CODE_IMAGE_QR_CENSOR) {
            startQrCensor();
        } else if (requestCode == REQUEST_CODE_QR_SCAN) {
            startQrScan();
        } else if (requestCode == REQUEST_CODE_IMAGE_PORTRAIT) {
            startPortrait();
        }
    }

    public void onImagePicked(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_CODE_IMAGE_BLUR_FACE) {
            pushFaceEditor(data);
        } else if (requestCode == REQUEST_CODE_IMAGE_NST_APPLY_PICTURE) {
            pushStyleEditor(data);
        } else if (requestCode == REQUEST_CODE_IMAGE_QR_CENSOR) {
            pushQrCensorEditor(data);
        } else if (requestCode == REQUEST_CODE_IMAGE_PORTRAIT) {
            pushPortraitEditor(data);
        }
    }

    /**
     * Push the interactive face editor page for the picked images
     */
    private void pushFaceEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : pickedUris) {
            uris.add(uri.toString());
        }
        mNavigator.push(Routes.FACE_EDITOR_PAGE, new UriList(uris));
    }

    /**
     * Push the interactive style editor page for the picked images
     */
    private void pushStyleEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : pickedUris) {
            uris.add(uri.toString());
        }
        mNavigator.push(Routes.STYLE_EDITOR_PAGE, new UriList(uris));
    }

    /**
     * Push the interactive QR censor editor page for the picked images
     */
    private void pushQrCensorEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : pickedUris) {
            uris.add(uri.toString());
        }
        mNavigator.push(Routes.QR_CENSOR_EDITOR_PAGE, new UriList(uris));
    }

    /**
     * Push the interactive portrait editor page for the picked images
     */
    private void pushPortraitEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : pickedUris) {
            uris.add(uri.toString());
        }
        mNavigator.push(Routes.PORTRAIT_EDITOR_PAGE, new UriList(uris));
    }

    /**
     * Push the live camera QR scanner page, no route args needed
     */
    private void pushQrScanner() {
        mNavigator.push(Routes.QR_SCANNER_PAGE);
    }

    private List<Uri> collectPickedUris(Intent data) {
        List<Uri> uriList = new ArrayList<>();
        if (data == null) {
            return uriList;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            int count = clipData.getItemCount();
            for (int i = 0; i < count; i++) {
                uriList.add(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uriList.add(data.getData());
        }
        return uriList;
    }
}
