package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import m.co.rh.id.a_jarwis.ml_engine.model.ProgressListener;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Photo restore engine over the NAFNet model (see {@link NafNetProcessor}).
 * Two paths: {@link #deblurPreview(Bitmap, int)} (one capped forward) and
 * {@link #deblurFull(Bitmap, int, int, ProgressListener)}
 * (tiled full-resolution save). Heavy native work — serialize calls;
 * inputs never recycled, returned bitmaps caller owned; app-lifetime net
 * (teardown-poison note on {@link NafNetProcessor}), no release here.
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
        return mMLEngine.get().isRestoreAvailable();
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
}
