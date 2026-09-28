package m.co.rh.id.a_jarwis.ml_engine.provider.notifier;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadError;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadProgress;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;

/**
 * Notifies subscribers about AI model download events:
 * all events (progress/succeeded/failed) are emitted by ModelDownloader;
 * ModelDownloadWorker additionally emits a failed event with a null model type
 * when the work input is invalid.
 * The flows are hot: events are only received by subscriptions active at emit time,
 * a consumer that attaches later must seed its initial state by other means
 * (e.g. querying WorkManager directly).
 */
public class ModelChangeNotifier {
    private Subject<ModelDownloadProgress> mProgressSubject;
    private Subject<ModelType> mSucceededSubject;
    private Subject<ModelDownloadError> mFailedSubject;

    public ModelChangeNotifier() {
        mProgressSubject = PublishSubject.<ModelDownloadProgress>create().toSerialized();
        mSucceededSubject = PublishSubject.<ModelType>create().toSerialized();
        mFailedSubject = PublishSubject.<ModelDownloadError>create().toSerialized();
    }

    /**
     * Emits a download progress event for the given model type
     * with the current bytes done/total counts.
     */
    public void downloadProgress(ModelType modelType, long bytesDone, long bytesTotal) {
        mProgressSubject.onNext(new ModelDownloadProgress(modelType, bytesDone, bytesTotal));
    }

    public void downloadSucceeded(ModelType modelType) {
        mSucceededSubject.onNext(modelType);
    }

    /**
     * @param modelType may be null when the failure is caused by an unknown model type id
     */
    public void downloadFailed(ModelType modelType, String errorMessage) {
        mFailedSubject.onNext(new ModelDownloadError(modelType, errorMessage));
    }

    public Flowable<ModelDownloadProgress> getProgressFlow() {
        return Flowable.fromObservable(mProgressSubject, BackpressureStrategy.BUFFER);
    }

    public Flowable<ModelType> getSucceededFlow() {
        return Flowable.fromObservable(mSucceededSubject, BackpressureStrategy.BUFFER);
    }

    public Flowable<ModelDownloadError> getFailedFlow() {
        return Flowable.fromObservable(mFailedSubject, BackpressureStrategy.BUFFER);
    }
}
