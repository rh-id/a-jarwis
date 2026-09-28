package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import java.io.IOException;

/**
 * Thrown when a downloaded model fails checksum/length validation even after a clean retry.
 * This is a permanent failure, retrying the same URL will not help.
 */
public class ModelChecksumException extends IOException {

    public ModelChecksumException(String message) {
        super(message);
    }
}
