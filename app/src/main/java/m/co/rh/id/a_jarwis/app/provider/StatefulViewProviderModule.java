package m.co.rh.id.a_jarwis.app.provider;

import m.co.rh.id.a_jarwis.app.provider.command.CommandProviderModule;
import m.co.rh.id.a_jarwis.base.provider.RxProviderModule;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class StatefulViewProviderModule implements ProviderModule {

    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerModule(new RxProviderModule());
        providerRegistry.registerModule(new CommandProviderModule());
    }
}
