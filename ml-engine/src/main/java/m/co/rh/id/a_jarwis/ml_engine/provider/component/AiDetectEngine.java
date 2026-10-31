package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * AI-generated-image detection engine of the CommunityForensics
 * DeepfakeDet-ViT model: classifies one bitmap as AI-generated (fake) or
 * real with a single probability (pFake in 0..1).
 * <p>
 * The facade exists so the :app pages never import org.opencv: the heavy
 * lifting is the {@link AiImageDetectProcessor} behind the
 * {@link MLEngineInstance} cache, this engine only mediates access to it.
 * <p>
 * Lifecycle policy: the cached processor is created lazily on the first
 * classify and KEPT for the page session — a per-classify re-parse of the
 * ~87 MB model (a seconds-long operation) would wreck the check-another-
 * image UX, and the round-2 spike measured ~278 MB native retained after
 * the inference (real, but far below the SAM/LaMa retained-workspace
 * levels that force the magic eraser's eager per-step releases). The
 * detector page releases the processor on dispose through {@link #release()}
 * (delegating to {@link MLEngineInstance#releaseAiImageDetectProcessor()}),
 * matching the FaceEditorPage releaseFaceModels precedent: the next
 * session lazily recreates the processor from the model file on demand.
 * <p>
 * The processing is a heavy native operation, callers are expected to run
 * it serialized (e.g. on a single background executor) and to never invoke
 * {@link #classify(Bitmap)} concurrently (the processor's
 * no-concurrent-calls contract, see AiImageDetectProcessor).
 */
public class AiDetectEngine {

    private final ProviderValue<MLEngineInstance> mMLEngine;

    public AiDetectEngine(Provider provider) {
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    /**
     * Classify the given bitmap: probability that the image is AI-generated.
     *
     * @param input bitmap to be classified, never recycled by this method
     * @return pFake in 0..1, the probability of the AI-generated class,
     * 1 - result is the probability of the real class
     */
    public float classify(Bitmap input) {
        return mMLEngine.get().getAiImageDetectProcessor().classify(input);
    }

    /**
     * Release the cached processor (frees its native net, see
     * {@link MLEngineInstance#releaseAiImageDetectProcessor()}), called by
     * the detector page on dispose. The next {@link #classify(Bitmap)}
     * lazily recreates the processor from the model file on demand. A no-op
     * when no processor is cached.
     */
    public void release() {
        mMLEngine.get().releaseAiImageDetectProcessor();
    }
}
