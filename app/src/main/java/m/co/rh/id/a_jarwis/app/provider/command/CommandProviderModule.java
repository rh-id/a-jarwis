package m.co.rh.id.a_jarwis.app.provider.command;

import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class CommandProviderModule implements ProviderModule {
    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerLazy(BlurFaceCommand.class, () -> new BlurFaceCommand(provider));
        providerRegistry.registerLazy(NSTApplyCommand.class, () -> new NSTApplyCommand(provider));
    }
}
