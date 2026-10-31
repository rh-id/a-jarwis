package m.co.rh.id.a_jarwis.ml_engine.provider;

import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.AiDetectEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.FaceEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MagicEraserEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.ModelDownloader;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.PersonSegEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.QREngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.STEngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.notifier.ModelChangeNotifier;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderModule;
import m.co.rh.id.aprovider.ProviderRegistry;

public class MLEngineProviderModule implements ProviderModule {
    @Override
    public void provides(ProviderRegistry providerRegistry, Provider provider) {
        providerRegistry.registerAsync(MLEngineInstance.class, () -> new MLEngineInstance(provider));
        providerRegistry.registerLazy(FaceEngine.class, () -> new FaceEngine(provider));
        providerRegistry.registerLazy(QREngine.class, () -> new QREngine(provider));
        providerRegistry.registerLazy(STEngine.class, () -> new STEngine(provider));
        providerRegistry.registerLazy(PersonSegEngine.class,
                () -> new PersonSegEngine(provider));
        providerRegistry.registerLazy(MagicEraserEngine.class,
                () -> new MagicEraserEngine(provider));
        providerRegistry.registerLazy(AiDetectEngine.class,
                () -> new AiDetectEngine(provider));
        providerRegistry.registerLazy(ModelDownloader.class,
                () -> new ModelDownloader(provider.get(ILogger.class),
                        provider.get(FileHelper.class),
                        provider.get(ModelChangeNotifier.class)));
        providerRegistry.registerLazy(ModelChangeNotifier.class,
                () -> new ModelChangeNotifier());
    }

    /**
     * Drain the cached native processors of the engine instance
     * synchronously: the registry entry dispose of
     * {@link MLEngineInstance} runs asynchronously on DefaultProvider's
     * static executor, which can still be pending when the next
     * instrumentation class starts loading models on the memory-constrained
     * CI emulator — draining synchronously here (the registry is still
     * intact during the module dispose) makes that async call an idempotent
     * no-op.
     */
    @Override
    public void dispose(Provider provider) {
        provider.get(MLEngineInstance.class).dispose(provider.getContext());
    }
}
