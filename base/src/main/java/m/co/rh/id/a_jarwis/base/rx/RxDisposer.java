package m.co.rh.id.a_jarwis.base.rx;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.disposables.Disposable;
import m.co.rh.id.aprovider.ProviderDisposable;

/**
 * Helper class to help manage Rx disposable instances
 */
public class RxDisposer implements ProviderDisposable {
    private Map<String, Disposable> disposableMap;

    public RxDisposer() {
        disposableMap = new HashMap<>();
    }

    public void add(String uniqueKey, Disposable disposable) {
        Disposable existing = disposableMap.remove(uniqueKey);
        if (existing != null) {
            existing.dispose();
        }
        disposableMap.put(uniqueKey, disposable);
    }

    /**
     * Dispose only the disposable registered under the given key (no-op
     * when absent), e.g. a user-requested cancel of one running pipeline
     */
    public void dispose(String uniqueKey) {
        Disposable disposable = disposableMap.remove(uniqueKey);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    public void dispose() {
        if (!disposableMap.isEmpty()) {
            for (Map.Entry<String, Disposable> entry : disposableMap.entrySet()) {
                entry.getValue().dispose();
            }
            disposableMap.clear();
        }
    }

    @Override
    public void dispose(Context context) {
        dispose();
    }
}
