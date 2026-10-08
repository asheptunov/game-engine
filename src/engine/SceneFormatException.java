package engine;

import java.io.IOException;

/** Checked syntax/schema/limit failure while reading a scene file. */
public final class SceneFormatException extends IOException {
    public SceneFormatException(String message) {
        super(message);
    }

    public SceneFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
