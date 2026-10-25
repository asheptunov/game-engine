package game;

import engine.*;

import java.nio.file.Path;

/** Explicitly test-only, window-free child using the production fatal handler and game owner. */
public final class FatalChild {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.endsWith("library")) { library(mode); return; }
        String backend = mode.startsWith("gpu") || mode.equals("missing-module") ? "gpu" : "cpu";
        var diagnostics = new GameDiagnostics();
        var handler = new GameCrashHandler("world", backend, 128, 80, Path.of(args[1]), diagnostics);
        var navigation = new GameNavigation();
        navigation.fixedResolution(128, 80);
        if (mode.equals("cpu-after")) engine.CpuFaultSession.armed = false;
        handler.releasePointer(() -> { navigation.release(); System.err.println("pointer released"); });
        if (mode.equals("cleanup-hang")) handler.cleanup(() -> {
            for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
        });
        if (mode.equals("cleanup-error")) handler.cleanup(() -> { throw new IllegalStateException("secondary cleanup cause"); });
        GameRenderer.SessionFactory factory = (world, view, settings, selected) -> {
            System.err.println("renderer startup: " + selected);
            if (mode.equals("validation") || mode.equals("missing-module"))
                return RenderEngine.openSession(world, view, settings, selected);
            if (selected.equals("cpu")) return CpuFaultSession.open(world, view, settings);
            try {
                return (RenderSession) Class.forName("gpu.GpuFaultSession")
                        .getMethod("open", WorldSnapshot.class, RenderView.class, RenderSettings.class, String.class)
                        .invoke(null, world, view, settings, mode);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        };
        GameRenderer renderer;
        try {
            renderer = new GameRenderer(navigation, SampleGameMain.world("world"), backend,
                    GameRenderer::copyImage, new GameRenderer.RuntimeHooks(diagnostics, failure -> {
                        handler.fatal(failure);
                        if (mode.equals("worker-hang")) {
                            for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
                        }
                        if (mode.equals("repeated")) handler.fatal(new RenderFailure(backend,
                                "secondary", new IllegalStateException("must not replace first cause"), java.util.Map.of()));
                    }, factory));
            if (!mode.startsWith("cleanup-")) handler.cleanup(renderer::close);
        } catch (Throwable failure) {
            handler.fatal(failure instanceof RenderFailure reported ? reported
                    : new RenderFailure(backend, "initialization", failure, java.util.Map.of()));
            Thread.sleep(30000);
            throw new AssertionError("fatal initialization did not exit");
        }
        if (mode.equals("validation") || mode.equals("cpu-after")) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (renderer.frame() == null && System.nanoTime() < deadline) Thread.sleep(10);
            if (renderer.frame() == null) throw new AssertionError("No initial frame");
            if (mode.equals("validation")) {
                var before = renderer.settings();
                try { renderer.configure(s -> s.withPathDepth(100)); throw new AssertionError("edit accepted"); }
                catch (IllegalArgumentException expected) { }
                if (!renderer.settings().equals(before)) throw new AssertionError("Invalid edit changed state");
                renderer.close();
                System.out.println("recoverable validation; CPU relaunch passed");
                return;
            }
            CpuFaultSession.armed = true;
            renderer.restart();
        }
        Thread.sleep(30000);
        throw new AssertionError("fatal execution did not exit");
    }
    private static void library(String mode) throws Exception {
        var source = SampleGameMain.world("world");
        var lighting = GameLighting.defaults();
        var world = new WorldSnapshot(0, source.instances(), java.util.List.of(), java.util.List.of(lighting.sun()), lighting.sky());
        var navigation = new GameNavigation();
        navigation.fixedResolution(128, 80);
        var settings = RenderSettings.defaults().withPathDepth(2).withWorkers(2);
        RenderSession session;
        if (mode.equals("cpu-library")) session = CpuFaultSession.open(world, navigation.view(), settings);
        else session = (RenderSession) Class.forName("gpu.GpuFaultSession")
                .getMethod("open", WorldSnapshot.class, RenderView.class, RenderSettings.class, String.class)
                .invoke(null, world, navigation.view(), settings, "gpu-execution");
        var observed = new java.util.concurrent.atomic.AtomicReference<RenderFailure>();
        session.onFailure(observed::set);
        session.request();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (observed.get() == null && System.nanoTime() < deadline) Thread.sleep(1);
        var original = observed.get();
        if (original == null || session.failure() != original || original.getCause() == null)
            throw new AssertionError("Missing typed failure/cause");
        var late = new java.util.concurrent.atomic.AtomicReference<RenderFailure>();
        session.onFailure(late::set);
        if (late.get() != original) throw new AssertionError("Late subscriber lost first failure");
        try { session.request(); throw new AssertionError("Failed session restarted"); }
        catch (RenderFailure expected) { if (expected != original) throw new AssertionError("Cause replaced"); }
        session.close();
        session.close();
        if (!session.closed()) throw new AssertionError("Close failed");
        System.out.println("library retains first failure without terminating host");
    }

}
