package m.co.rh.id.a_jarwis.ml_engine.workmanager.model;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

public class STApplySerialFile implements Serializable {
    private File inputFile;
    private ArrayList<Integer> themes;

    public STApplySerialFile(File inputFile, Collection<Integer> themes) {
        this.inputFile = inputFile;
        this.themes = new ArrayList<>(themes);
    }

    public File getInputFile() {
        return inputFile;
    }

    public ArrayList<Integer> getThemes() {
        return themes;
    }

}
