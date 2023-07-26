package m.co.rh.id.a_jarwis.ml_engine.workmanager.model;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;

public class BlurFaceSerialFile implements Serializable {
    private File inputFile;
    private ArrayList<File> faces;
    private boolean exclude;

    public BlurFaceSerialFile(File inputFile) {
        this.inputFile = inputFile;
    }

    public boolean isExclude() {
        return exclude;
    }

    public void setExclude(boolean exclude) {
        this.exclude = exclude;
    }

    public File getInputFile() {
        return inputFile;
    }

    public ArrayList<File> getFaces() {
        return faces;
    }

    public void setFaces(ArrayList<File> faces) {
        this.faces = faces;
    }
}
