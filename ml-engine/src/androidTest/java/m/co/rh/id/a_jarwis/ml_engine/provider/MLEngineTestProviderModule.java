package m.co.rh.id.a_jarwis.ml_engine.provider;

import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import m.co.rh.id.a_jarwis.base.provider.BaseProviderModule;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class MLEngineTestProviderModule implements ProviderModule {

    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.setSkipSameType(true);
        providerRegistry.registerLazy(WorkManager.class, () -> {
            WorkManagerTestInitHelper.initializeTestWorkManager(provider.getContext());
            return WorkManager.getInstance(provider.getContext());
        });
        providerRegistry.registerModule(new BaseProviderModule());
        providerRegistry.registerModule(new MLEngineProviderModule());
    }
}
