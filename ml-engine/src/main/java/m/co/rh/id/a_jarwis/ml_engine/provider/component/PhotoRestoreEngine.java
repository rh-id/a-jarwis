package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.graphics.Bitmap;

import m.co.rh.id.a_jarwis.ml_engine.model.ProgressListener;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Photo restore engine over the NAFNet deblur model (see
 * {@link NafNetProcessor}) and the Swin2SR 4x upscale model (see
 * {@link Swin2srProcessor}). Each has a preview path (one capped pass) and
 * a tiled full-resolution save path. Heavy native work — serialize calls;
 * inputs never recycled, returned bitmaps caller owned; app-lifetime nets
 * (teardown-poison note on the processors), no release here.
 */
public class PhotoRestoreEngine {

    private final ProviderValue<MLEngineInstance> mMLEngine;

    public PhotoRestoreEngine(Provider provider) {
        mMLEngine = provider.lazyGet(MLEngineInstance.class);
    }

    /**
     * @return true when the NAFNet deblur model is downloaded and usable;
     * kept for API symmetry with the engine facades
     */
    public boolean isAvailable() {
        return mMLEngine.get().isModelAvailable(ModelType.NAFNET_DEBLUR);
    }

    /**
     * Deblur in one forward at a capped working resolution, the preview
     * path. The cap is mandatory (~8.3 KB forward RSS per input pixel),
     * pass {@link NafNetProcessor#suggestedWorkingCap(android.content.Context)}.
     *
     * @param input bitmap to be deblurred, never recycled
     * @param maxWorkingSide cap for the longest image side
     */
    public Bitmap deblurPreview(Bitmap input, int maxWorkingSide) {
        return mMLEngine.get().getNafNetProcessor().process(input,
                maxWorkingSide);
    }

    /**
     * Deblur at full resolution through the tile grid, the save path —
     * one bounded forward per tile, blended (see
     * {@code NafNetProcessor.processTiled}).
     *
     * @param input bitmap to be deblurred, never recycled
     * @param tileSize square tile side, positive
     * @param overlap in 0..tileSize/2
     * @param listener per-tile progress, may be null; caller's thread
     */
    public Bitmap deblurFull(Bitmap input, int tileSize, int overlap,
                             ProgressListener listener) {
        return mMLEngine.get().getNafNetProcessor().processTiled(input,
                tileSize, overlap, listener);
    }

    /**
     * @return true when the Swin2SR upscale model is downloaded and usable;
     * kept for API symmetry with the engine facades
     */
    public boolean isUpscaleAvailable() {
        return mMLEngine.get().isModelAvailable(ModelType.SWIN2SR_UPSCALE);
    }

    /**
     * Opaque model id of the Swin2SR upscale model, used to key download
     * requests through ModelDownloadDialog/ModelDownloadWorker (the engines
     * are the only model id producers).
     *
     * @return the opaque model id of the upscale model
     */
    public String upscaleModelId() {
        return ModelType.SWIN2SR_UPSCALE.getId();
    }

    /**
     * Opaque model id of the NAFNet deblur model, used to key download
     * requests through ModelDownloadDialog/ModelDownloadWorker (the engines
     * are the only model id producers).
     *
     * @return the opaque model id of the deblur model
     */
    public String deblurModelId() {
        return ModelType.NAFNET_DEBLUR.getId();
    }

    /**
     * RAM-tiered input cap for the upscale pipelines, bounding the peak
     * merge memory at ~47 B per output pixel (see
     * {@link Swin2srProcessor#suggestedUpscaleCap(Context)}).
     *
     * @param context any context
     * @return 1024 on >= 8 GB RAM, 768 on >= 6 GB, 512 on >= 4 GB, 384 below
     */
    public int suggestedUpscaleCap(Context context) {
        return Swin2srProcessor.suggestedUpscaleCap(context);
    }

    /**
     * Upscale in one capped pass, the preview path (the 4x result is
     * resized back to the input dimensions). The cap is mandatory (~47 B
     * merge memory per output pixel, i.e. 16 output pixels per input
     * pixel), pass
     * {@link Swin2srProcessor#suggestedUpscaleCap(android.content.Context)}.
     *
     * @param input bitmap to be upscaled, never recycled
     * @param maxWorkingSide cap for the longest image side
     * @param listener per-tile progress, may be null; caller's thread
     */
    public Bitmap upscalePreview(Bitmap input, int maxWorkingSide,
                                 ProgressListener listener) {
        return mMLEngine.get().getSwin2srProcessor().process(input,
                maxWorkingSide, listener);
    }

    /**
     * Upscale at full resolution through the tile grid, the save path —
     * one bounded forward per tile, blended (see
     * {@code Swin2srProcessor.processTiled}); the input longest side must
     * be pre-capped (see
     * {@link Swin2srProcessor#suggestedUpscaleCap(android.content.Context)}).
     *
     * @param input bitmap to be upscaled, never recycled, pre-capped
     * @param overlap in 0..{@code Swin2srProcessor.TILE_IN}/2, input pixels
     * @param listener per-tile progress, may be null; caller's thread
     */
    public Bitmap upscaleFull(Bitmap input, int overlap,
                              ProgressListener listener) {
        return mMLEngine.get().getSwin2srProcessor().processTiled(input,
                overlap, listener);
    }
}
