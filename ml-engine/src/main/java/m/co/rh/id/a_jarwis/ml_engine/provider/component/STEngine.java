package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.base.util.SerializeUtils;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.STApplyWorkRequest;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.model.STApplySerialFile;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class STEngine {
    public static final int THEME_MOSAIC = 1;
    public static final int THEME_CANDY = 2;
    public static final int THEME_RAIN_PRINCESS = 3;
    public static final int THEME_UDNIE = 4;
    public static final int THEME_POINTILISM = 5;

    private static final String TAG = "NSTEngine";

    private final WorkManager mWorkManager;
    private final ILogger mLogger;
    private final ProviderValue<MLEngineInstance> mMLEngine;
    private final FileHelper mFileHelper;

    public STEngine(Provider provider) {
        mWorkManager = provider.get(WorkManager.class);
        mLogger = provider.get(ILogger.class);
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
        mFileHelper = provider.get(FileHelper.class);
    }

    public Bitmap applyMosaic(Bitmap bitmap) {
        return mMLEngine.get().getNSTMosaic().process(bitmap);
    }

    public Bitmap applyCandy(Bitmap bitmap) {
        return mMLEngine.get().getNSTCandy().process(bitmap);
    }

    public Bitmap applyRainPrincess(Bitmap bitmap) {
        return mMLEngine.get().getNSTRainPrincess().process(bitmap);
    }

    public Bitmap applyUdnie(Bitmap bitmap) {
        return mMLEngine.get().getNSTUdnie().process(bitmap);
    }

    public Bitmap applyPointilism(Bitmap bitmap) {
        return mMLEngine.get().getNSTPointilism().process(bitmap);
    }

    public Bitmap apply(Bitmap bitmap, int theme) {
        switch (theme) {
            case THEME_MOSAIC:
                return applyMosaic(bitmap);
            case THEME_CANDY:
                return applyCandy(bitmap);
            case THEME_RAIN_PRINCESS:
                return applyRainPrincess(bitmap);
            case THEME_UDNIE:
                return applyUdnie(bitmap);
            case THEME_POINTILISM:
                return applyPointilism(bitmap);
            default:
                return bitmap;
        }
    }

    public void enqueueST(File imageFile, int theme) {
        ObjectOutputStream oos = null;
        try {
            File serialFile = mFileHelper.createTempFile();
            oos = new ObjectOutputStream(new FileOutputStream(serialFile));
            STApplySerialFile STApplySerialFile = new STApplySerialFile(imageFile, theme);
            oos.writeObject(STApplySerialFile);
            oos.close();
            Data.Builder inputBuilder = new Data.Builder();
            inputBuilder.putByteArray(Params.SERIAL_FILE, SerializeUtils.serialize(serialFile));
            OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(STApplyWorkRequest.class)
                    .setInputData(inputBuilder.build())
                    .build();
            mWorkManager.enqueue(oneTimeWorkRequest);
        } catch (Exception e) {
            mLogger.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (IOException e) {
                    mLogger.e(TAG, e.getMessage(), e);
                }
            }
        }
    }
}
