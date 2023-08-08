package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

public class SelectedTheme implements Serializable {
    private ArrayList<Integer> selectedThemes;

    public SelectedTheme(Collection<Integer> selectedThemes) {
        this.selectedThemes = new ArrayList<>(selectedThemes);
    }

    public ArrayList<Integer> getSelectedThemes() {
        return selectedThemes;
    }
}
