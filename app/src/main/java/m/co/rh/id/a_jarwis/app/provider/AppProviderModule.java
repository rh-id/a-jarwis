package m.co.rh.id.a_jarwis.app.provider;

import android.app.Application;

import m.co.rh.id.a_jarwis.app.provider.command.CommandProviderModule;
import m.co.rh.id.a_jarwis.base.provider.BaseProviderModule;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.RxProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.provider.MlEngineProviderModule;
import m.co.rh.id.a_jarwis.settings.provider.SettingsProviderModule;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class AppProviderModule implements ProviderModule {

    private Application mApplication;

    public AppProviderModule(Application application) {
        mApplication = application;
    }

    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerModule(new BaseProviderModule());
        providerRegistry.registerModule(new CommandProviderModule());
        providerRegistry.registerModule(new RxProviderModule());
        providerRegistry.registerModule(new SettingsProviderModule());
        providerRegistry.registerModule(new MlEngineProviderModule());

        providerRegistry.registerPool(IStatefulViewProvider.class, () -> new StatefulViewProvider(provider));
        // it is safer to register navigator last in case it needs dependency from all above, provider can be passed here
        providerRegistry.register(NavigatorProvider.class, () -> new NavigatorProvider(mApplication, provider));
    }
}
