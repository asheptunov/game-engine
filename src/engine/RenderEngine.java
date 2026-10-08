package engine;

/** Entry point for consumers; no DI container, window, console, or preset is required. */
public final class RenderEngine {
    private RenderEngine() {}

    public static RenderSession openSession(
            WorldSnapshot world, RenderView view, RenderSettings settings) {
        return new DefaultRenderSession(world, view, settings);
    }

    /**
     * Playground migration bridge. Remove after commands operate directly on immutable document
     * snapshots.
     */
    public static RenderSession openLegacySession(ViewportState state, DirectRgbTracer tracer) {
        return new DefaultRenderSession(state, tracer);
    }
}
