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
        return mlEngine.get().getNSTMosaic().process(bitmap);
    }

    public Bitmap applyCandy(Bitmap bitmap) {
        return mlEngine.get().getNSTCandy().process(bitmap);
    }

    public Bitmap applyRainPrincess(Bitmap bitmap) {
        return mlEngine.get().getNSTRainPrincess().process(bitmap);
    }

    public Bitmap applyUdnie(Bitmap bitmap) {
        return mlEngine.get().getNSTUdnie().process(bitmap);
    }
}
