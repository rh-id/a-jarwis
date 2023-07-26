package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.Serializable;

public class SelectedTheme implements Serializable {
    private int selectedTheme;

    public SelectedTheme(int selectedTheme) {
        this.selectedTheme = selectedTheme;
    }

    public int getSelectedTheme() {
        return selectedTheme;
    }
}
