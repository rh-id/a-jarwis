package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.content.SharedPreferences;

import m.co.rh.id.aprovider.Provider;

/**
 * Persists the last-used timestamp of each AI model, keyed by the opaque
 * model id obtained from the engine facades, in a plain private
 * SharedPreferences file named "ml-engine-model-usage". The storage page
 * (ModelStorageManager) reads the timestamps to show a relative
 * "last used" hint next to each model, and a delete clears the entry
 * together with the model file.
 * A timestamp is only recorded for an actual use: it is written from
 * {@link MLEngineInstance#requireModelFile(MLEngineInstance.ModelType)}
 * (the single point where a model file is loaded for a native parse), the
 * availability gates never mark it (checking whether a model exists is
 * not using it).
 * Writes use {@link SharedPreferences#apply()}: safe here because the app
 * is single-process, callers are off the main thread and the store has no
 * read-modify-write use case (each entry is an independent timestamp).
 */
public class ModelUsageStore {
    private static final String PREFS_NAME = "ml-engine-model-usage";

    private final SharedPreferences mSharedPreferences;

    public ModelUsageStore(Provider provider) {
        Context appContext = provider.getContext().getApplicationContext();
        mSharedPreferences = appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Mark the model behind the given opaque model id as used: records the
     * current wall-clock time as its last-used time
     *
     * @param modelId opaque model id as obtained from the engine facades
     */
    public void markUsed(String modelId) {
        mSharedPreferences.edit()
                .putLong(modelId, System.currentTimeMillis())
                .apply();
    }

    /**
     * Last-used wall-clock timestamp of the model behind the given opaque
     * model id
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @return the timestamp of the last {@link #markUsed(String)}, or 0 when
     * the model was never used
     */
    public long getLastUsed(String modelId) {
        return mSharedPreferences.getLong(modelId, 0L);
    }

    /**
     * Forget the last-used timestamp of the model behind the given opaque
     * model id, called by the delete flow (ModelStorageManager.deleteModel)
     * so a deleted model reports "never used" again
     *
     * @param modelId opaque model id as obtained from the engine facades
     */
    public void clear(String modelId) {
        mSharedPreferences.edit()
                .remove(modelId)
                .apply();
    }
}
