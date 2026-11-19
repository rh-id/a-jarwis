package m.co.rh.id.a_jarwis.app.ui.page;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.R;
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
    private static final int REQUEST_CODE_IMAGE_MAGIC_ERASER = 8;
    private static final int REQUEST_CODE_IMAGE_AI_DETECT = 9;
    private static final int REQUEST_CODE_IMAGE_RESTORE = 10;

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

    public void startMagicEraser() {
        requestWriteExternalStoragePermission(() ->
                startIfMagicEraserModelsAvailable(() ->
                        pickImage(REQUEST_CODE_IMAGE_MAGIC_ERASER, false)),
                REQUEST_CODE_IMAGE_MAGIC_ERASER);
    }

    public void startAiDetect() {
        requestWriteExternalStoragePermission(() -> startIfAiDetectModelsAvailable(() ->
                pickImage(REQUEST_CODE_IMAGE_AI_DETECT, false)), REQUEST_CODE_IMAGE_AI_DETECT);
    }

    /**
     * Start the photo restore (NAFNet deblur) flow. The 64-bit gate runs
     * FIRST, before anything else: the NAFNet forward memory scales at
     * ~8.3 KB per input pixel and the OpenCV dnn failure mode on 32-bit is
     * UNRELIABLE — the spike (issue #5, measured 2026-11-18) recorded a
     * catchable CvException at the 512 px forward but a hard SIGSEGV inside
     * TBB already at the 192 px one, so a 32-bit device would die at an
     * unpredictable point instead of failing gracefully. On 32-bit the
     * feature is refused with a toast before any model download or picker
     * opens (the gate also mirrors the app-lifetime net policy of
     * {@link m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance}:
     * a process that can never run the net safely must never parse it).
     */
    public void startPhotoRestore() {
        Activity activity = mNavigator.getActivity();
        if (Build.SUPPORTED_64_BIT_ABIS.length == 0) {
            Toast.makeText(activity, R.string.photo_restore_error_64bit_required,
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestWriteExternalStoragePermission(() ->
                startIfPhotoRestoreModelsAvailable(() ->
                        pickImage(REQUEST_CODE_IMAGE_RESTORE, false)),
                REQUEST_CODE_IMAGE_RESTORE);
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

    private void startIfMagicEraserModelsAvailable(ContinueAction continueAction) {
        Activity activity = mNavigator.getActivity();
        ArrayList<ModelType> missingModels = new ArrayList<>();
        if (!ModelCatalog.isAvailable(activity, ModelType.EFFICIENT_SAM)) {
            missingModels.add(ModelType.EFFICIENT_SAM);
        }
        if (!ModelCatalog.isAvailable(activity, ModelType.LAMA)) {
            missingModels.add(ModelType.LAMA);
        }
        if (missingModels.isEmpty()) {
            continueAction.run();
        } else {
            startModelDownload(missingModels, continueAction);
        }
    }

    private void startIfAiDetectModelsAvailable(ContinueAction continueAction) {
        Activity activity = mNavigator.getActivity();
        ArrayList<ModelType> missingModels = new ArrayList<>();
        if (!ModelCatalog.isAvailable(activity, ModelType.AI_IMAGE_DETECT)) {
            missingModels.add(ModelType.AI_IMAGE_DETECT);
        }
        if (missingModels.isEmpty()) {
            continueAction.run();
        } else {
            startModelDownload(missingModels, continueAction);
        }
    }

    private void startIfPhotoRestoreModelsAvailable(ContinueAction continueAction) {
        Activity activity = mNavigator.getActivity();
        ArrayList<ModelType> missingModels = new ArrayList<>();
        if (!ModelCatalog.isAvailable(activity, ModelType.NAFNET_DEBLUR)) {
            missingModels.add(ModelType.NAFNET_DEBLUR);
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
        pickImage(requestCode, true);
    }

    /**
     * Open the system image picker.
     *
     * @param allowMultiple true to allow a multi image pick, false to restrict
     *                      the pick to a single image (the EXTRA_ALLOW_MULTIPLE
     *                      extra is omitted)
     */
    private void pickImage(int requestCode, boolean allowMultiple) {
        Activity activity = mNavigator.getActivity();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        if (allowMultiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
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
        } else if (requestCode == REQUEST_CODE_IMAGE_MAGIC_ERASER) {
            startMagicEraser();
        } else if (requestCode == REQUEST_CODE_IMAGE_AI_DETECT) {
            startAiDetect();
        } else if (requestCode == REQUEST_CODE_IMAGE_RESTORE) {
            startPhotoRestore();
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
        } else if (requestCode == REQUEST_CODE_IMAGE_MAGIC_ERASER) {
            pushMagicEraserEditor(data);
        } else if (requestCode == REQUEST_CODE_IMAGE_AI_DETECT) {
            pushAiDetect(data);
        } else if (requestCode == REQUEST_CODE_IMAGE_RESTORE) {
            pushPhotoRestoreEditor(data);
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
     * Push the interactive magic eraser editor page, the edit works on a
     * single image so only the first picked uri is taken
     */
    private void pushMagicEraserEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        uris.add(pickedUris.get(0).toString());
        mNavigator.push(Routes.MAGIC_ERASER_EDITOR_PAGE, new UriList(uris));
    }

    /**
     * Push the AI image detector page, the detection works on a single
     * image so only the first picked uri is taken
     */
    private void pushAiDetect(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        uris.add(pickedUris.get(0).toString());
        mNavigator.push(Routes.AI_DETECT_PAGE, new UriList(uris));
    }

    /**
     * Push the photo restore editor page, the deblur works on a single
     * image so only the first picked uri is taken
     */
    private void pushPhotoRestoreEditor(Intent data) {
        List<Uri> pickedUris = collectPickedUris(data);
        if (pickedUris.isEmpty()) {
            return;
        }
        ArrayList<String> uris = new ArrayList<>();
        uris.add(pickedUris.get(0).toString());
        mNavigator.push(Routes.PHOTO_RESTORE_EDITOR_PAGE, new UriList(uris));
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
