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

import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.base.provider.component.helper.MediaHelper;
import m.co.rh.id.a_jarwis.base.util.SerializeUtils;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.NSTEngine;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.model.NSTApplySerialFile;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class NSTApplyWorkRequest extends Worker {
    private static final String TAG = "NSTApplyWorkRequest";

    public NSTApplyWorkRequest(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    public Result doWork() {
        Provider provider = BaseApplication.of(getApplicationContext()).getProvider();
        ILogger logger = provider.get(ILogger.class);
        MediaHelper mediaHelper = provider.get(MediaHelper.class);
        NSTEngine nstEngine = provider.get(NSTEngine.class);

        File inputFile = null;
        ObjectInputStream ois = null;
        try {
            byte[] serialFileB = getInputData().getByteArray(Params.SERIAL_FILE);
            File serialFile = SerializeUtils.deserialize(serialFileB);
            ois = new ObjectInputStream(new FileInputStream(serialFile));
            NSTApplySerialFile nstApplySerialFile = (NSTApplySerialFile) ois.readObject();
            inputFile = nstApplySerialFile.getInputFile();
            int theme = nstApplySerialFile.getTheme();

            Bitmap input = BitmapFactory.decodeFile(inputFile.getAbsolutePath());
            Bitmap applied = nstEngine.apply(input, theme);
            String fileName = inputFile.getName();
            if (applied != null) {
                mediaHelper.insertImage(applied, fileName, fileName);
                applied.recycle();
                input.recycle();
                logger.i(TAG, getApplicationContext()
                        .getString(m.co.rh.id.a_jarwis.base.R.string.done_processing_, fileName));
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
