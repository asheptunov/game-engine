package engine;

import java.time.Instant;
import java.util.Map;

/** First unrecoverable backend failure. Libraries report it; only the host decides to exit. */
public final class RenderFailure extends IllegalStateException {
    private final String backend;
    private final String phase;
    private final Instant timestamp = Instant.now();
    private final Map<String, String> context;

    public RenderFailure(
            String backend, String phase, Throwable cause, Map<String, String> context) {
        super(backend + " " + phase + " failed: " + cause, cause);
        this.backend = backend;
        this.phase = phase;
        this.context = Map.copyOf(context);
    }

    public String backend() {
        return backend;
    }

    public String phase() {
        return phase;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Map<String, String> context() {
        return context;
    }
}
