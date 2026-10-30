package m.co.rh.id.a_jarwis.ml_engine.model;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * One committed erase step: the strokes that produced the erase
 * (resolution-independent normalized strokes), replayed at any resolution
 * by the preview restore and the full resolution save/share.
 * A committed batch is final, the undo/reset only affect the current
 * strokes.
 * Only holds a serializable field so it is safe to keep inside the
 * serializable editor state, never hold Bitmap/Uri here.
 */
public class StrokeBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The strokes of the committed erase step, in normalized 0..1
     * coordinates (see {@link EditorStroke})
     */
    private final ArrayList<EditorStroke> mStrokes;

    public StrokeBatch(ArrayList<EditorStroke> strokes) {
        mStrokes = strokes;
    }

    public ArrayList<EditorStroke> getStrokes() {
        return mStrokes;
    }
}
