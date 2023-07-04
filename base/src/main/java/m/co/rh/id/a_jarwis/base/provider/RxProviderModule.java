package m.co.rh.id.a_jarwis.base.provider;

import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class RxProviderModule implements ProviderModule {

    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerAsync(RxDisposer.class, RxDisposer::new);
    }
}
