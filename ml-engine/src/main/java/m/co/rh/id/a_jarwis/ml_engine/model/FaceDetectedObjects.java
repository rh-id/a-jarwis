package m.co.rh.id.a_jarwis.ml_engine.model;

import java.util.List;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;

public class FaceDetectedObjects extends DetectedObjects {

    private List<BoundingBox> boundingBoxes;

    public FaceDetectedObjects(List<String> classNames, List<Double> probabilities, List<BoundingBox> boundingBoxes) {
        super(classNames, probabilities, boundingBoxes);
        this.boundingBoxes = boundingBoxes;
    }

    public List<BoundingBox> getBoundingBoxes() {
        return boundingBoxes;
    }
}
