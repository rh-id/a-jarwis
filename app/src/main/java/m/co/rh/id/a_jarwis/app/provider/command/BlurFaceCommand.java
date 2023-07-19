package m.co.rh.id.a_jarwis.app.provider.command;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

import io.reactivex.rxjava3.core.Single;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class BlurFaceCommand {
    private static String TAG = "BlurFaceCommand";
    private Context mAppContext;
    private ExecutorService mExecutorService;
    private ILogger mLogger;
    private FileHelper mFileHelper;
    private FaceEngine mFaceEngine;

    public BlurFaceCommand(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mExecutorService = provider.get(ExecutorService.class);
        mLogger = provider.get(ILogger.class);
        mFileHelper = provider.get(FileHelper.class);
        mFaceEngine = provider.get(FaceEngine.class);
    }

    public Single<File> execute(Uri uriFile) {
        return execute(uriFile, null);
    }

    public Single<File> execute(Uri uriFile, Collection<File> excludeFiles) {
        return execute(uriFile, excludeFiles, true);
    }

    public Single<File> execute(Uri uriFile, Collection<File> faces, boolean isExclude) {
        return Single.fromFuture(mExecutorService.submit(() -> {
            String fullPath = uriFile.getPath();
            int cut = fullPath.lastIndexOf("/");
            File result = mFileHelper
                    .createTempFile(fullPath.substring(cut + 1), uriFile);
            mFaceEngine.enqueueBlurFace(result, faces, isExclude);
            return result;
        }));
    }
}
