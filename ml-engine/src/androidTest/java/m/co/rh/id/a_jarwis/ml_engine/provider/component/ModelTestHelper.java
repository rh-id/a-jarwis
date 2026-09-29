package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import java.io.IOException;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.aprovider.Provider;

/**
 * Seeds the AI models required by the engine tests.
 * The models are not bundled inside the apk, so before any test that uses the engines,
 * each {@link ModelType} is checked with {@link ModelCatalog#isAvailable} and when missing
 * downloaded (blocking, sequential; the helper does not observe the progress
 * events broadcast during the downloads) via {@link ModelDownloader}
 * into the proper filesDir path. Requires network access on the test device.
 */
public final class ModelTestHelper {

    private ModelTestHelper() {
    }

    public static void installModels(Provider provider) throws IOException {
        Context context = provider.getContext().getApplicationContext();
        ModelDownloader modelDownloader = provider.get(ModelDownloader.class);
        for (ModelType modelType : ModelType.values()) {
            if (ModelCatalog.isAvailable(context, modelType)) {
                continue;
            }
            try {
                modelDownloader.download(context, modelType);
            } catch (IOException e) {
                throw new IOException("Failed to download test model "
                        + modelType.getDisplayName()
                        + "; the instrumented test app needs working network access"
                        + " (android.permission.INTERNET) on the test device", e);
            }
            if (!ModelCatalog.isAvailable(context, modelType)) {
                throw new IOException("Model " + modelType.getDisplayName()
                        + " download reported success but the model file is still missing");
            }
        }
    }
}
