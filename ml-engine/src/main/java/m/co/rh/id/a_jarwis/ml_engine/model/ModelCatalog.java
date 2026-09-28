package m.co.rh.id.a_jarwis.ml_engine.model;

import android.content.Context;

import java.io.File;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.STEngine;

/**
 * Maps each {@link ModelType} to its target file inside the app filesDir,
 * following the same path structure used by {@link MLEngineInstance} when loading the models.
 */
public final class ModelCatalog {

    private ModelCatalog() {
    }

    public static File getModelFile(Context context, ModelType modelType) {
        File filesDir = context.getFilesDir();
        switch (modelType) {
            case FACE_DETECT:
                return new File(filesDir, MLEngineInstance.FACE_DETECT_PATH
                        + "/" + MLEngineInstance.FACE_DETECT_FILE);
            case FACE_RECOGNIZER:
                return new File(filesDir, MLEngineInstance.FACE_RECOGNIZER_PATH
                        + "/" + MLEngineInstance.FACE_RECOGNIZER_FILE);
            case NST_MOSAIC:
                return new File(filesDir, MLEngineInstance.NEURAL_STYLE_TRANSFER_PATH
                        + "/" + MLEngineInstance.NST_MOSAIC_FILE);
            case NST_CANDY:
                return new File(filesDir, MLEngineInstance.NEURAL_STYLE_TRANSFER_PATH
                        + "/" + MLEngineInstance.NST_CANDY_FILE);
            case NST_RAIN_PRINCESS:
                return new File(filesDir, MLEngineInstance.NEURAL_STYLE_TRANSFER_PATH
                        + "/" + MLEngineInstance.NST_RAIN_PRINCESS_FILE);
            case NST_UDNIE:
                return new File(filesDir, MLEngineInstance.NEURAL_STYLE_TRANSFER_PATH
                        + "/" + MLEngineInstance.NST_UDNIE_FILE);
            case NST_POINTILISM:
                return new File(filesDir, MLEngineInstance.NEURAL_STYLE_TRANSFER_PATH
                        + "/" + MLEngineInstance.NST_POINTILISM_FILE);
            default:
                throw new IllegalArgumentException("Unknown model type: " + modelType);
        }
    }

    /**
     * @return true when the model file is already downloaded and usable.
     * Runs the legacy file name migration (see {@link #migrateLegacyIfNeeded}) when needed
     */
    public static boolean isAvailable(Context context, ModelType modelType) {
        File modelFile = getModelFile(context, modelType);
        if (modelFile.exists()) {
            return true;
        }
        return migrateLegacyIfNeeded(modelType, modelFile);
    }

    /**
     * Installs before on-demand download bundled the NST models inside the APK under the
     * legacy nst_*_9.onnx names. When the model is missing under its current name but the
     * legacy file exists in the same directory, rename it instead of re-downloading ~32 MB.
     * Returns true when after this call the model file exists.
     */
    private static boolean migrateLegacyIfNeeded(ModelType modelType, File modelFile) {
        String legacyFileName = getLegacyFileName(modelType);
        if (legacyFileName == null) {
            return false;
        }
        File legacyFile = new File(modelFile.getParentFile(), legacyFileName);
        if (!legacyFile.exists()) {
            return false;
        }
        if (legacyFile.renameTo(modelFile)) {
            return true;
        }
        // benign race when two threads migrate at the same time,
        // the second rename fails, just check whether the first one succeeded
        return modelFile.exists();
    }

    private static String getLegacyFileName(ModelType modelType) {
        switch (modelType) {
            case NST_MOSAIC:
                return "nst_mosaic_9.onnx";
            case NST_CANDY:
                return "nst_candy_9.onnx";
            case NST_RAIN_PRINCESS:
                return "nst_rain_princess_9.onnx";
            case NST_UDNIE:
                return "nst_udnie_9.onnx";
            case NST_POINTILISM:
                return "nst_pointilism_9.onnx";
            default:
                // face models never had a legacy name
                return null;
        }
    }

    /**
     * @return ModelType for a neural style transfer theme constant defined in {@link STEngine}
     */
    public static ModelType fromTheme(int theme) {
        switch (theme) {
            case STEngine.THEME_MOSAIC:
                return ModelType.NST_MOSAIC;
            case STEngine.THEME_CANDY:
                return ModelType.NST_CANDY;
            case STEngine.THEME_RAIN_PRINCESS:
                return ModelType.NST_RAIN_PRINCESS;
            case STEngine.THEME_UDNIE:
                return ModelType.NST_UDNIE;
            case STEngine.THEME_POINTILISM:
                return ModelType.NST_POINTILISM;
            default:
                throw new IllegalArgumentException("Unknown theme: " + theme);
        }
    }
}
