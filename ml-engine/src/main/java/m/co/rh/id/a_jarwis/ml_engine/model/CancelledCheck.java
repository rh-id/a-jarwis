package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Abort hook for the long-running engine pipelines ({@code applyBatches},
 * {@code eraseObject}): the caller flags when its consumer (e.g. a disposed
 * editor page) is gone, so a replay or erase can stop before the next native
 * run re-parses the released dnn nets into the app-scoped processor cache
 * nobody will release anymore.
 */
@FunctionalInterface
public interface CancelledCheck {
    /**
     * @return true when the caller's consumer is gone and the running
     * pipeline must abort
     */
    boolean isCancelled();
}
