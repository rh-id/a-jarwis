package m.co.rh.id.a_jarwis.ml_engine.provider.notifier;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadError;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelDownloadProgress;

/**
 * Notifies subscribers about AI model download events, keyed by the opaque
 * model id obtained from the engine facades:
 * all events (progress/succeeded/failed) are emitted by ModelDownloader;
 * ModelDownloadWorker additionally emits a failed event with a null model id
 * when the work input is invalid.
 * The flows are hot: events are only received by subscriptions active at emit time,
 * a consumer that attaches later must seed its initial state by other means
 * (e.g. querying WorkManager directly).
 */
public class ModelChangeNotifier {
    private Subject<ModelDownloadProgress> mProgressSubject;
    private Subject<String> mSucceededSubject;
    private Subject<ModelDownloadError> mFailedSubject;

    public ModelChangeNotifier() {
        mProgressSubject = PublishSubject.<ModelDownloadProgress>create().toSerialized();
        mSucceededSubject = PublishSubject.<String>create().toSerialized();
        mFailedSubject = PublishSubject.<ModelDownloadError>create().toSerialized();
    }

    /**
     * Emits a download progress event for the given model id
     * with the current bytes done/total counts.
     */
    public void downloadProgress(String modelId, long bytesDone, long bytesTotal) {
        mProgressSubject.onNext(new ModelDownloadProgress(modelId, bytesDone, bytesTotal));
    }

    public void downloadSucceeded(String modelId) {
        mSucceededSubject.onNext(modelId);
    }

    /**
     * @param modelId may be null when the failure is not tied to a known model
     *                (e.g. missing/unknown work input id)
     */
    public void downloadFailed(String modelId, String errorMessage) {
        mFailedSubject.onNext(new ModelDownloadError(modelId, errorMessage));
    }

    public Flowable<ModelDownloadProgress> getProgressFlow() {
        return Flowable.fromObservable(mProgressSubject, BackpressureStrategy.BUFFER);
    }

    public Flowable<String> getSucceededFlow() {
        return Flowable.fromObservable(mSucceededSubject, BackpressureStrategy.BUFFER);
    }

    public Flowable<ModelDownloadError> getFailedFlow() {
        return Flowable.fromObservable(mFailedSubject, BackpressureStrategy.BUFFER);
    }
}
