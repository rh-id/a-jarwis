package m.co.rh.id.a_jarwis.app.provider.command;

import android.net.Uri;

import java.io.File;
import java.util.concurrent.ExecutorService;

import io.reactivex.rxjava3.core.Single;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.NSTEngine;
import m.co.rh.id.aprovider.Provider;

public class NSTApplyCommand {
    private final ExecutorService mExecutorService;
    private final FileHelper mFileHelper;
    private final NSTEngine mNSTEngine;

    public NSTApplyCommand(Provider provider) {
        mExecutorService = provider.get(ExecutorService.class);
        mFileHelper = provider.get(FileHelper.class);
        mNSTEngine = provider.get(NSTEngine.class);
    }

    public Single<File> execute(Uri uriFile, int theme) {
        return Single.fromFuture(mExecutorService.submit(() -> {
            String fullPath = uriFile.getPath();
            int cut = fullPath.lastIndexOf("/");
            File result = mFileHelper
                    .createImageTempFile(fullPath.substring(cut + 1), uriFile);
            mNSTEngine.enqueueNsT(result, theme);
            return result;
        }));
    }
}
