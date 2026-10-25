package engine;

/** Entry point for consumers; no DI container, window, console, or preset is required. */
public final class RenderEngine {
    private RenderEngine() {}

    public static RenderCapabilities capabilities(String backend) {
        return RenderCapabilities.forBackend(backend);
    }

    /** Explicit potentially expensive probe; never called by a frame tick or capability query. */
    public static RenderAvailability probe(String backend) {
        if (capabilities(backend) == RenderCapabilities.CPU) {
            return new RenderAvailability(RenderAvailability.State.AVAILABLE, "Java CPU renderer");
        }
        try {
            return (RenderAvailability)
                    Class.forName("gpu.GpuProbe").getMethod("probe").invoke(null);
        } catch (ClassNotFoundException | NoClassDefFoundError failure) {
            return new RenderAvailability(
                    RenderAvailability.State.UNAVAILABLE,
                    "GPU module/dependencies missing: " + failure);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new RenderAvailability(
                    RenderAvailability.State.ERROR, "GPU probe failed: " + failure);
        }
    }

    public static RenderSession openSession(
            WorldSnapshot world, RenderView view, RenderSettings settings) {
        RenderCapabilities.CPU.validate(world, view, settings);
        try {
            return new DefaultRenderSession(world, view, settings);
        } catch (RuntimeException | Error failure) {
            throw new RenderFailure(
                    "cpu",
                    "initialization",
                    failure,
                    java.util.Map.of(
                            "initialization",
                            "CPU session construction incomplete",
                            "device",
                            "not applicable (CPU)"));
        }
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
        // Unsupported input is distinct from module/device availability, even without GPU jars.
        RenderCapabilities.GPU.validate(world, view, settings);
        try {
            return (RenderSession)
                    Class.forName("gpu.GpuRenderSession")
                            .getConstructor(
                                    WorldSnapshot.class, RenderView.class, RenderSettings.class)
                            .newInstance(world, view, settings);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new RenderFailure(
                    "gpu",
                    "initialization",
                    failure.getCause(),
                    java.util.Map.of(
                            "initialization",
                            "GPU module loaded; session creation failed",
                            "device",
                            "unavailable"));
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new RenderFailure(
                    "gpu",
                    "initialization",
                    failure,
                    java.util.Map.of(
                            "initialization",
                            "GPU module loading failed",
                            "device",
                            "unavailable"));
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
