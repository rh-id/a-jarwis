package m.co.rh.id.a_jarwis.ml_engine.workmanager.model;

import java.io.File;
import java.io.Serializable;

public class STApplySerialFile implements Serializable {
    private File inputFile;
    private int theme;

    public STApplySerialFile(File inputFile, int theme) {
        this.inputFile = inputFile;
        this.theme = theme;
    }

    public File getInputFile() {
        return inputFile;
    }

    public int getTheme() {
        return theme;
    }

}
