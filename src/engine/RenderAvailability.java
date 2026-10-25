package engine;

/** Explicit device-probe result. ERROR is a failed probe, never a missing-hardware skip. */
public record RenderAvailability(State state, String detail) {
    public enum State {
        AVAILABLE,
        UNAVAILABLE,
        ERROR
    }
}
