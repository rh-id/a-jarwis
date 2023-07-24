package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class NSTEngine {

    private final ProviderValue<MLEngineInstance> mlEngine;

    public NSTEngine(Provider provider) {
        mlEngine = provider.lazyGet(MLEngineInstance.class);
    }

    public Bitmap applyMosaic(Bitmap bitmap) {
        NSTProcessor nstMosaic = mlEngine.get().getNSTMosaic();
        return nstMosaic.process(bitmap);
    }
}
