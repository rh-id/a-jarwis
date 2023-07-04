package m.co.rh.id.a_jarwis.ml_engine.model;

import java.io.Serializable;

public class SimilarityScore implements Serializable {
    private double distance;
    private double similarity;

    public SimilarityScore(double distance, double similarity) {
        this.distance = distance;
        this.similarity = similarity;
    }

    public double getDistance() {
        return distance;
    }

    public double getSimilarity() {
        return similarity;
    }
}
