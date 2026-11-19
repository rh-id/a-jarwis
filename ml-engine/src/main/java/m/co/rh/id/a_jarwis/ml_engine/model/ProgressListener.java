package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Common progress hook for long-running engine pipelines: reports
 * done/total unit counts (e.g. tiles of a tiled pipeline, currently the
 * photo-restore save). Invoked on the CALLER'S thread while the engine's
 * monitor is held — never call back into the engine from it
 * (self-deadlock); hand off to another thread (e.g. the UI thread)
 * instead. Units are pipeline-specific (today: tiles).
 */
@FunctionalInterface
public interface ProgressListener {
    /**
     * @param done units finished so far (1..total)
     * @param total total unit count of the running pipeline
     */
    void onProgress(int done, int total);
}
