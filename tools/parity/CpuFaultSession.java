package engine;

/** Test-only entry point compiled by fatal-check.ps1, never shipped in game builds. */
public final class CpuFaultSession {
    public static volatile boolean armed = true;

    public static RenderSession open(WorldSnapshot world, RenderView view, RenderSettings settings) {
        var state = new ViewportState(view.camera().sensor(), view.width(), view.height());
        var tracer = new DirectRgbTracer(state);
        tracer.beforeWorker = () -> {
            if (armed) throw new IllegalStateException("injected CPU worker failure",
                    new ArithmeticException("original CPU cause"));
        };
        var session = RenderEngine.openLegacySession(state, tracer);
        session.update(world, view, settings);
        return session;
    }
}
