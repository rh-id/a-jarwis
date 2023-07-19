package m.co.rh.id.a_jarwis.ml_engine.workmanager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;

import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.util.SerializeUtils;
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
        Provider provider = BaseApplication.of(getApplicationContext()).getProvider();
        ILogger logger = provider.get(ILogger.class);
        MediaHelper mediaHelper = provider.get(MediaHelper.class);
        FaceEngine faceEngine = provider.get(FaceEngine.class);

        File inputFile = null;
        ObjectInputStream ois = null;
        try {
            byte[] serialFileB = getInputData().getByteArray(Params.SERIAL_FILE);
            File serialFile = SerializeUtils.deserialize(serialFileB);
            ois = new ObjectInputStream(new FileInputStream(serialFile));
            BlurFaceSerialFile blurFaceSerialFile = (BlurFaceSerialFile) ois.readObject();
            inputFile = blurFaceSerialFile.getInputFile();
            ArrayList<File> fileArrayList = blurFaceSerialFile.getExcludeFiles();

            ArrayList<Bitmap> bitmaps = new ArrayList<>();
            if (fileArrayList != null && !fileArrayList.isEmpty()) {
                for (File file : fileArrayList) {
                    bitmaps.add(BitmapFactory.decodeStream(new FileInputStream(file)));
                }
            }
            Bitmap face = BitmapFactory.decodeFile(inputFile.getAbsolutePath());
            Bitmap blurredFace = faceEngine.blurFace(face, bitmaps);
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
            if (inputFile != null) {
                inputFile.delete();
            }
            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException e) {
                    // Leave blank
                }
            }
        }
        return Result.success();
    }
}
