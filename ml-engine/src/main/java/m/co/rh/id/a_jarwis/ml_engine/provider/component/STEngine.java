package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class STEngine {
    public static final int THEME_MOSAIC = 1;
    public static final int THEME_CANDY = 2;
    public static final int THEME_RAIN_PRINCESS = 3;
    public static final int THEME_UDNIE = 4;
    public static final int THEME_POINTILISM = 5;

    private final ProviderValue<MLEngineInstance> mMLEngine;

    public STEngine(Provider provider) {
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    public Bitmap applyMosaic(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(THEME_MOSAIC).process(bitmap);
    }

    public Bitmap applyCandy(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(THEME_CANDY).process(bitmap);
    }

    public Bitmap applyRainPrincess(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(THEME_RAIN_PRINCESS).process(bitmap);
    }

    public Bitmap applyUdnie(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(THEME_UDNIE).process(bitmap);
    }

    public Bitmap applyPointilism(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(THEME_POINTILISM).process(bitmap);
    }

    /**
     * Apply the neural style transfer model of the given theme on the given bitmap,
     * the bitmap is returned unchanged when the theme has no matching style.
     * The processing is a heavy native operation, callers are expected to run it
     * serialized (e.g. on a single background executor)
     *
     * @param bitmap input bitmap, never recycled by this method
     * @param theme  neural style transfer theme constant defined in this class
     * @return the stylized bitmap, a new bitmap instance unless the theme has no
     * matching style (the input instance is returned as is)
     */
    public Bitmap apply(Bitmap bitmap, int theme) {
        switch (theme) {
            case THEME_MOSAIC:
                return applyMosaic(bitmap);
            case THEME_CANDY:
                return applyCandy(bitmap);
            case THEME_RAIN_PRINCESS:
                return applyRainPrincess(bitmap);
            case THEME_UDNIE:
                return applyUdnie(bitmap);
            case THEME_POINTILISM:
                return applyPointilism(bitmap);
            default:
                return bitmap;
        }
    }
}
