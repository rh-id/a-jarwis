package m.co.rh.id.a_jarwis.app.ui.page;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.alogger.ILogger;

class HomeImageProcessor {
    private static final String TAG = "HomePage";

    private final Context mContext;
    private final ILogger mLogger;
    private final ExecutorService mExecutorService;
    private final RxDisposer mRxDisposer;

    HomeImageProcessor(IStatefulViewProvider svProvider) {
        mContext = svProvider.getContext();
        mLogger = svProvider.get(ILogger.class);
        mExecutorService = svProvider.get(ExecutorService.class);
        mRxDisposer = svProvider.get(RxDisposer.class);
    }

    void processImages(String disposerTag, List<Uri> uris, Function<Uri, Single<File>> commandFactory) {
        mRxDisposer.add(disposerTag, Flowable.fromIterable(uris)
                .map(uri -> commandFactory.apply(uri).blockingGet())
                .subscribeOn(Schedulers.from(mExecutorService))
                .doOnError(throwable -> consumeResult(null, throwable))
                .subscribe(file -> consumeResult(file, null))
        );
    }

    void processImage(String disposerTag, Uri uri, Function<Uri, Single<File>> commandFactory) {
        mRxDisposer.add(disposerTag, commandFactory.apply(uri)
                .subscribeOn(Schedulers.from(mExecutorService))
                .subscribe(this::consumeResult));
    }

    private void consumeResult(File file, Throwable throwable) {
        if (throwable != null) {
            mLogger.e(TAG, throwable.getMessage(), throwable);
        } else {
            mLogger.i(TAG, mContext.getString(m.co.rh.id.a_jarwis.R.string.processing_,
                    file.getName()));
        }
    }
}
