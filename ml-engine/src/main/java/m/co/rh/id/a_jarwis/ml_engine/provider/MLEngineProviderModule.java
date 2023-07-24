package m.co.rh.id.a_jarwis.ml_engine.provider;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.NSTEngine;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class MLEngineProviderModule implements ProviderModule {
    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerAsync(MLEngineInstance.class, () -> new MLEngineInstance(provider));
        providerRegistry.registerLazy(FaceEngine.class, () -> new FaceEngine(provider));
        providerRegistry.registerLazy(NSTEngine.class, () -> new NSTEngine(provider));
    }
}
