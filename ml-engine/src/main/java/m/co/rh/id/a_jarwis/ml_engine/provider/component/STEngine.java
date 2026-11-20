package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

public class STEngine {
    public static final int THEME_MOSAIC = 1;
    public static final int THEME_CANDY = 2;
    public static final int THEME_RAIN_PRINCESS = 3;
    public static final int THEME_UDNIE = 4;
    public static final int THEME_POINTILISM = 5;

    private final ProviderValue<MLEngineInstance> mMLEngine;

    /**
     * Map a neural style transfer theme constant to its model type, the
     * internal theme-to-model resolution of this facade (the engine instance
     * is keyed by model type, never by theme).
     *
     * @param theme neural style transfer theme constant defined in this class
     * @return ModelType for the given theme constant
     * @throws IllegalArgumentException on an unknown theme
     */
    private static ModelType modelTypeForTheme(int theme) {
        switch (theme) {
            case THEME_MOSAIC:
                return ModelType.NST_MOSAIC;
            case THEME_CANDY:
                return ModelType.NST_CANDY;
            case THEME_RAIN_PRINCESS:
                return ModelType.NST_RAIN_PRINCESS;
            case THEME_UDNIE:
                return ModelType.NST_UDNIE;
            case THEME_POINTILISM:
                return ModelType.NST_POINTILISM;
            default:
                throw new IllegalArgumentException("Unknown theme: " + theme);
        }
    }

    public STEngine(Provider provider) {
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    public Bitmap applyMosaic(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(modelTypeForTheme(THEME_MOSAIC))
                .process(bitmap);
    }

    public Bitmap applyCandy(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(modelTypeForTheme(THEME_CANDY))
                .process(bitmap);
    }

    public Bitmap applyRainPrincess(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(modelTypeForTheme(THEME_RAIN_PRINCESS))
                .process(bitmap);
    }

    public Bitmap applyUdnie(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(modelTypeForTheme(THEME_UDNIE))
                .process(bitmap);
    }

    public Bitmap applyPointilism(Bitmap bitmap) {
        return mMLEngine.get().getSTProcessor(modelTypeForTheme(THEME_POINTILISM))
                .process(bitmap);
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

    /**
     * Check whether the neural style transfer model of the given theme is
     * downloaded and usable, so the style editor can badge the tiles whose
     * model still needs a download.
     *
     * @param theme neural style transfer theme constant defined in this class
     * @return true when the model file of the given theme is already downloaded
     * @throws IllegalArgumentException on a theme without a matching model type
     */
    public boolean isNSTAvailable(int theme) {
        return mMLEngine.get().isModelAvailable(modelTypeForTheme(theme));
    }

    /**
     * Opaque model id of the given theme's style model, used to key download
     * requests through ModelDownloadDialog/ModelDownloadWorker.
     *
     * @param theme neural style transfer theme constant defined in this class
     * @return the opaque model id of the theme's style model
     * @throws IllegalArgumentException on an unknown theme
     */
    public String modelIdForTheme(int theme) {
        return modelTypeForTheme(theme).getId();
    }
}
