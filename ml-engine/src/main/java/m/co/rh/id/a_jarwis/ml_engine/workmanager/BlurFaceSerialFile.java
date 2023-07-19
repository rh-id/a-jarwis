package m.co.rh.id.a_jarwis.ml_engine.workmanager;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;

public class BlurFaceSerialFile implements Serializable {
    private File inputFile;
    private ArrayList<File> excludeFiles;

    public BlurFaceSerialFile(File inputFile) {
        this.inputFile = inputFile;
    }

    public File getInputFile() {
        return inputFile;
    }

    public void setInputFile(File inputFile) {
        this.inputFile = inputFile;
    }

    public ArrayList<File> getExcludeFiles() {
        return excludeFiles;
    }

    public void setExcludeFiles(ArrayList<File> excludeFiles) {
        this.excludeFiles = excludeFiles;
    }
}
