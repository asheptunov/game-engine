package engine;

/** Entry point for consumers; no DI container, window, console, or preset is required. */
public final class RenderEngine {
    private RenderEngine() {}

    public static RenderSession openSession(
            WorldSnapshot world, RenderView view, RenderSettings settings) {
        return new DefaultRenderSession(world, view, settings);
    }

    /** Explicit backend selection; the default overload never loads GPU/native classes. */
    public static RenderSession openSession(
            WorldSnapshot world, RenderView view, RenderSettings settings, String backend) {
        if ("cpu".equals(backend)) {
            return openSession(world, view, settings);
        }
        if (!"gpu".equals(backend)) {
            throw new IllegalArgumentException("Backend must be cpu or gpu");
        }
        try {
            return (RenderSession)
                    Class.forName("gpu.GpuRenderSession")
                            .getConstructor(
                                    WorldSnapshot.class, RenderView.class, RenderSettings.class)
                            .newInstance(world, view, settings);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalArgumentException(
                    "GPU unavailable: "
                            + failure.getCause().getMessage()
                            + "; relaunch ./game.ps1 -Backend cpu",
                    failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException(
                    "GPU module unavailable; run ./setup-gpu.ps1 and ./gpu-build.ps1, or relaunch"
                            + " ./game.ps1 -Backend cpu",
                    failure);
        }
    }

    /**
     * Playground migration bridge. Remove after commands operate directly on immutable document
     * snapshots.
     */
    public static RenderSession openLegacySession(ViewportState state, DirectRgbTracer tracer) {
        return new DefaultRenderSession(state, tracer);
    }
}
