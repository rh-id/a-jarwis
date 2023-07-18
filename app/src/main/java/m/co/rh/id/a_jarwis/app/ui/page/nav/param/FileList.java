package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

public class FileList implements Serializable {
    private ArrayList<File> files;

    public FileList(Collection<File> files) {
        this.files = new ArrayList<>(files);
    }

    public ArrayList<File> getFiles() {
        return files;
    }
}
