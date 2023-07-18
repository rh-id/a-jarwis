package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.Serializable;

public class SelectedChoice implements Serializable {
    public static final int NEGATIVE = -1;
    public static final int NO_SELECT = 0;
    public static final int POSITIVE = 1;

    private int selectedChoice;

    public SelectedChoice() {
        this.selectedChoice = NO_SELECT;
    }

    public int getSelectedChoice() {
        return selectedChoice;
    }

    public void setSelectedChoice(int selectedChoice) {
        this.selectedChoice = selectedChoice;
    }
}
