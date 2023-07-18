package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.Serializable;

public class MessageText implements Serializable {
    private boolean enableCancel;
    private int title;
    private int body;

    public MessageText(int title, int body, boolean enableCancel) {
        this.title = title;
        this.body = body;
        this.enableCancel = enableCancel;
    }

    public int getTitle() {
        return title;
    }

    public int getBody() {
        return body;
    }

    public boolean isEnableCancel() {
        return enableCancel;
    }
}
