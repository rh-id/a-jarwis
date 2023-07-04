package m.co.rh.id.a_jarwis.app.provider;

import android.content.Context;

import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderDisposable;
import m.co.rh.id.aprovider.ProviderIsDisposed;
import m.co.rh.id.aprovider.ProviderValue;

public class StatefulViewProvider implements IStatefulViewProvider, ProviderDisposable, ProviderIsDisposed {
    private Provider mProvider;
    private boolean mIsDisposed;

    public StatefulViewProvider(Provider parentProvider) {
        mProvider = Provider.createNestedProvider("StatefulViewProvider", parentProvider,
                parentProvider.getContext(), new StatefulViewProviderModule());
    }

    @Override
    public <I> I get(Class<I> clazz) {
        return mProvider.get(clazz);
    }

    @Override
    public <I> I tryGet(Class<I> clazz) {
        return mProvider.tryGet(clazz);
    }

    @Override
    public <I> ProviderValue<I> lazyGet(Class<I> clazz) {
        return mProvider.lazyGet(clazz);
    }

    @Override
    public <I> ProviderValue<I> tryLazyGet(Class<I> clazz) {
        return mProvider.tryLazyGet(clazz);
    }

    @Override
    public Context getContext() {
        return mProvider.getContext();
    }

    @Override
    public void dispose() {
        if (mIsDisposed) return;
        mIsDisposed = true;
        if (mProvider != null) {
            mProvider.dispose();
            mProvider = null;
        }
    }

    @Override
    public void dispose(Context context) {
        dispose();
    }

    @Override
    public boolean isDisposed() {
        return mIsDisposed;
    }
}
