package m.co.rh.id.a_jarwis.ml_engine.workmanager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;

import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class BlurFaceWorkRequest extends Worker {
    private static final String TAG = "BlurFaceWorkRequest";

    private static final String RELATIVE_PATH = "a-jarwis/face_blur";

    public BlurFaceWorkRequest(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    public Result doWork() {
        File inputFile = new File(getInputData().getString(Params.ARGS_FILE_PATH));
        Provider provider = BaseApplication.of(getApplicationContext()).getProvider();
        ILogger logger = provider.get(ILogger.class);
        MediaHelper mediaHelper = provider.get(MediaHelper.class);
        FaceEngine faceEngine = provider.get(FaceEngine.class);
        try {
            Bitmap face = BitmapFactory.decodeFile(inputFile.getAbsolutePath());
            Bitmap blurredFace = faceEngine.blurFace(face);
            if (blurredFace != null) {
                mediaHelper.insertImage(blurredFace, inputFile.getName(), inputFile.getName());
                face.recycle();
                blurredFace.recycle();
            }
        } catch (Exception e) {
            logger.e(TAG, e.getMessage(), e);
            return Result.failure();
        }
        return Result.success();
    }
}
