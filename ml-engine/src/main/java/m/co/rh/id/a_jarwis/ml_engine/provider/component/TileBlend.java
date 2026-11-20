package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Shared tile-merge blend math of the tiled dnn processors, moved verbatim
 * from {@link NafNetProcessor}. UNITS CONTRACT: every
 * tileSize/overlap/tileLeft/tileTop/clipWidth/clipHeight argument lives in
 * ONE pixel space — the output/canvas space the accumulators are sized in.
 * A caller whose forward grid runs at a different resolution than its
 * accumulation canvas (Swin2SR: 64-px input tiles, 256-px output tiles)
 * must convert before calling: the ramp is built at the OUTPUT tile size
 * with the OUTPUT overlap, and the accumulate coordinates are canvas
 * coordinates.
 */
final class TileBlend {

    private TileBlend() {
    }

    /**
     * The shared per-tile blend weight Mat: per-axis edge ramps' product,
     * 1 at the center falling to 1/(overlap+1) at the edges. 2-D, bulk put
     * is safe (the zero-transfer bug is n-dim only).
     */
    static Mat buildWeightRamp(int tileSize, int overlap) {
        Mat ramp = new Mat(tileSize, tileSize, CvType.CV_32FC1);
        float[] buffer = new float[tileSize * tileSize];
        for (int y = 0; y < tileSize; y++) {
            float rampY = edgeRamp(y, overlap) * edgeRamp(tileSize - 1 - y,
                    overlap);
            for (int x = 0; x < tileSize; x++) {
                float rampX = edgeRamp(x, overlap)
                        * edgeRamp(tileSize - 1 - x, overlap);
                buffer[y * tileSize + x] = rampX * rampY;
            }
        }
        ramp.put(0, 0, buffer);
        return ramp;
    }

    /**
     * One linear edge ramp: 1 at the center, 1/(overlap+1) AT the edge
     * pixel — 0 would divide by zero at single-covered borders.
     */
    static float edgeRamp(int distanceToEdge, int overlap) {
        if (overlap <= 0 || distanceToEdge >= overlap) {
            return 1f;
        }
        return (distanceToEdge + 1f) / (overlap + 1f);
    }

    /**
     * Add the in-image region of one tile's weighted output into the
     * running sums (fresh destination per addition: no in-place aliasing).
     */
    static void accumulate(Mat sumWeighted, Mat sumWeight,
                           int tileLeft, int tileTop,
                           int clipWidth, int clipHeight,
                           Mat weightedTile, Mat ramp) {
        Mat weightedView = null;
        Mat rampView = null;
        Mat sumWeightedView = null;
        Mat sumWeightView = null;
        Mat weightedSum = null;
        Mat weightSum = null;
        try {
            weightedView = weightedTile.submat(0, clipHeight, 0, clipWidth);
            rampView = ramp.submat(0, clipHeight, 0, clipWidth);
            sumWeightedView = sumWeighted.submat(tileTop,
                    tileTop + clipHeight, tileLeft, tileLeft + clipWidth);
            sumWeightView = sumWeight.submat(tileTop,
                    tileTop + clipHeight, tileLeft, tileLeft + clipWidth);
            weightedSum = new Mat();
            Core.add(sumWeightedView, weightedView, weightedSum);
            weightedSum.copyTo(sumWeightedView);
            weightSum = new Mat();
            Core.add(sumWeightView, rampView, weightSum);
            weightSum.copyTo(sumWeightView);
        } finally {
            if (weightedView != null) {
                weightedView.release();
            }
            if (rampView != null) {
                rampView.release();
            }
            if (sumWeightedView != null) {
                sumWeightedView.release();
            }
            if (sumWeightView != null) {
                sumWeightView.release();
            }
            if (weightedSum != null) {
                weightedSum.release();
            }
            if (weightSum != null) {
                weightSum.release();
            }
        }
    }
}
