package m.co.rh.id.a_jarwis.app.ui.page.nav.param;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Navigation args containing picked image uris,
 * the uris are stored as string so this class stays java serializable
 */
public class UriList implements Serializable {
    private ArrayList<String> uris;

    public UriList(Collection<String> uris) {
        this.uris = new ArrayList<>(uris);
    }

    public ArrayList<String> getUris() {
        return uris;
    }
}
