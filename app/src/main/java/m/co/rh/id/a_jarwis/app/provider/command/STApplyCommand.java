package m.co.rh.id.a_jarwis.app.provider.command;

import android.net.Uri;

import java.io.File;
import java.util.concurrent.ExecutorService;

import io.reactivex.rxjava3.core.Single;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.STEngine;
import m.co.rh.id.aprovider.Provider;

public class STApplyCommand {
    private final ExecutorService mExecutorService;
    private final FileHelper mFileHelper;
    private final STEngine mSTEngine;

    public STApplyCommand(Provider provider) {
        mExecutorService = provider.get(ExecutorService.class);
        mFileHelper = provider.get(FileHelper.class);
        mSTEngine = provider.get(STEngine.class);
    }

    public Single<File> execute(Uri uriFile, int theme) {
        return Single.fromFuture(mExecutorService.submit(() -> {
            String fullPath = uriFile.getPath();
            int cut = fullPath.lastIndexOf("/");
            String fileNameWithExt = fullPath.substring(cut + 1);
            int lastDot = fileNameWithExt.lastIndexOf(".jpg");
            String fileName = fileNameWithExt;
            if (lastDot == -1) {
                fileName = fileNameWithExt + ".jpg";
            }
            File result = mFileHelper
                    .createImageTempFile(fileName, uriFile);
            mSTEngine.enqueueST(result, theme);
            return result;
        }));
    }
}
