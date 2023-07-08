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
            String fileName = inputFile.getName();
            if (blurredFace != null) {
                mediaHelper.insertImage(blurredFace, fileName, fileName);
                face.recycle();
                blurredFace.recycle();
                logger.i(TAG, getApplicationContext()
                        .getString(m.co.rh.id.a_jarwis.base.R.string.done_processing_, fileName));
            } else {
                logger.i(TAG, getApplicationContext()
                        .getString(m.co.rh.id.a_jarwis.base.R.string.no_image_detected_, fileName));
            }
        } catch (Exception e) {
            logger.e(TAG, e.getMessage(), e);
            return Result.failure();
        } finally {
            inputFile.delete();
        }
        return Result.success();
    }
}
