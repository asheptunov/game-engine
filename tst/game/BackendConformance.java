package game;

import engine.Material;
import engine.RenderAvailability;
import engine.RenderCapabilities;
import engine.RenderEngine;
import engine.RenderImage;
import engine.RenderSession;
import engine.RenderSettings;
import engine.RenderView;
import engine.Sky;
import engine.WorldSnapshot;
import engine.lights.PointLight;

import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The same observable session contract runs with either backend, without native imports. */
public final class BackendConformance {
    private BackendConformance() {}

    public static void main(String[] args) throws Exception {
        String backend = args.length == 0 ? "cpu" : args[0];
        var output = Path.of(args.length > 1 ? args[1] : "out/parity");
        Files.createDirectories(output);
        capabilities();
        lifecycle(backend);
        controls(backend);
        ParityImages.run(backend, output);
        Files.writeString(
                output.resolve(backend + "-conformance.txt"),
                "PASS: capabilities, atomic validation, completion, immutable leases, invalidation,"
                    + " batch equivalence, pause/suspend/resume/close, controls and fixtures\n");
        System.out.println("PASS " + backend + " shared conformance");
    }

    static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    static WorldSnapshot world(WorldSnapshot source, GameLighting lighting) {
        return new WorldSnapshot(
                0, source.instances(), List.of(), List.of(lighting.sun()), lighting.sky());
    }

    static RenderImage await(RenderSession session, long samples) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (image.generation() == session.progress(null).requestedGeneration()
                        && image.samples() >= samples) {
                    return image;
                }
                image.close();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No current image: " + session.status());
    }

    static float[] copy(RenderImage image) {
        var pixels = new float[image.width() * image.height() * 3];
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                for (int c = 0; c < 3; c++) {
                    pixels[(y * image.width() + x) * 3 + c] = image.rawPixels().value(c, x, y);
                }
            }
        }
        return pixels;
    }

    private static void capabilities() {
        var world = world(BlockWorld.gallery().snapshot(), GameLighting.defaults());
        var view = new GameNavigation().view();
        var settings = RenderSettings.defaults();
        require(
                RenderCapabilities.commonGameIssues(world, view, settings).isEmpty(),
                "gallery common profile");
        require(
                RenderEngine.probe("cpu").state() == RenderAvailability.State.AVAILABLE,
                "CPU probe without native module");
        for (var backend : RenderCapabilities.values()) {
            backend.validate(world, view, settings);
        }
        var invalidSettings =
                List.of(
                        settings.withPathDepth(3),
                        settings.withAcceleration(false),
                        settings.withTemporal(true, 1),
                        settings.withTemporalBudget(true, 1, 1),
                        settings.withInteractive(true, 16, 0, 0));
        for (var unsupported : invalidSettings) {
            require(
                    !RenderCapabilities.GPU.issues(world, view, unsupported).isEmpty(),
                    "GPU reports unsupported setting");
            RenderCapabilities.CPU.validate(world, view, unsupported);
        }
        try {
            RenderEngine.openSession(world, view, settings.withPathDepth(3), "gpu");
            throw new AssertionError("unsupported creation accepted");
        } catch (RenderCapabilities.UnsupportedInput expected) {
            require(
                    expected.issue().path().equals("settings.pathDepth"),
                    "preflight before loading native module");
        }
        var pointWorld =
                WorldSnapshot.of(world.instances(), List.of(new PointLight(new Vec3(0, 5, 0))));
        require(
                RenderCapabilities.GPU
                        .issues(pointWorld, view, settings)
                        .getFirst()
                        .path()
                        .equals("world.lights"),
                "light issue path");
        var mirror =
                world.instances()
                        .getFirst()
                        .withMaterial(
                                new Material("mirror", new Vec3(1, 1, 1), Material.Kind.MIRROR));
        var mirrorWorld = WorldSnapshot.of(List.of(mirror), List.of());
        RenderCapabilities.CPU.validate(mirrorWorld, view, settings);
        require(
                RenderCapabilities.GPU
                        .issues(mirrorWorld, view, settings)
                        .getFirst()
                        .path()
                        .contains(mirror.name()),
                "named material issue");
        boolean rejected = false;
        try {
            new WorldSnapshot(0, List.of(mirror), List.of(), world.lights(), world.sky());
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "shared unsupported lighting/material combination");
        require(
                !RenderCapabilities.GPU
                        .support(RenderCapabilities.Feature.WORKER_CONTROL)
                        .supported(),
                "GPU scheduling is not CPU scheduling");
        var orthographic = new RenderView(view.camera().withProjection("orthographic"), 64, 64);
        require(
                RenderCapabilities.GPU
                        .issues(world, orthographic, settings)
                        .getFirst()
                        .path()
                        .equals("view.camera.projection"),
                "projection issue");
        RenderCapabilities.CPU.validate(world, orthographic, settings);
        for (float fov : new float[] {1, 179}) {
            var extreme =
                    new RenderView(
                            view.camera().withFov(fov).withAperture(10).withFocus(.01f), 1600, 64);
            require(
                    RenderCapabilities.commonGameIssues(world, extreme, settings).isEmpty(),
                    "supported camera/grid limits");
        }
    }

    private static void lifecycle(String backend) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        var world = world(BlockWorld.gallery().snapshot(), GameLighting.defaults());
        var view = navigation.view();
        var settings = RenderSettings.defaults().withPathDepth(2).withSampleTarget(4);
        var session = RenderEngine.openSession(world, view, settings, backend);
        try (session) {
            var signal = new CountDownLatch(1);
            session.onImageAvailable(signal::countDown);
            try (var held = await(session, 4)) {
                require(signal.await(5, TimeUnit.SECONDS), "completion callback");
                var pixels = copy(held);
                long generation = held.generation();
                session.presented(held);
                session.update(world, view, settings.withSamplesPerBatch(2));
                require(
                        session.progress(null).requestedGeneration() == generation,
                        "batch edit retains generation");
                try {
                    session.update(world, null, settings);
                    throw new AssertionError("null view accepted");
                } catch (NullPointerException expected) {
                    require(
                            session.progress(null).requestedGeneration() == generation,
                            "invalid update is atomic");
                }
                session.onImageAvailable(null);
                session.update(world, view, settings.withRestartRevision(1).withSamplesPerBatch(4));
                try (var restarted = await(session, 4)) {
                    require(restarted.generation() != generation, "restart invalidates");
                    require(
                            java.util.Arrays.equals(pixels, copy(restarted)),
                            "batch equivalence / reproducible restart");
                }
                require(
                        java.util.Arrays.equals(pixels, copy(held)),
                        "held buffer remains immutable across restart");
            }
            releasedLease(session);
            pauseAndResume(session, world, view, settings);
            navigation.pose(new Vec3(1, 3, -5), .1, -.1);
            session.update(world, navigation.view(), settings.withRestartRevision(3));
            try (var moved = await(session, 4)) {
                require(moved.camera().equals(navigation.view().camera()), "captured camera");
            }
            var edited =
                    new WorldSnapshot(1, world.instances(), List.of(), world.lights(), Sky.BLACK);
            session.update(edited, navigation.view(), settings.withSampleTarget(1));
            try (var image = await(session, 1)) {
                require(image.samples() == 1, "world edit resets accumulation");
            }
            session.update(edited, navigation.view(), settings.withSampleTarget(0));
            session.request();
        }
        session.close();
        require(session.closed(), "idempotent close with active work");
        boolean rejected = false;
        try {
            session.request();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "closed session cannot request");
    }

    private static void releasedLease(RenderSession session) {
        var lease = session.acquireImage();
        require(lease != null, "acquire completed image");
        var pixels = lease.rawPixels();
        lease.close();
        boolean rejected = false;
        try {
            pixels.value(0, 0, 0);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "released pixels reject reads");
    }

    private static void pauseAndResume(
            RenderSession session, WorldSnapshot world, RenderView view, RenderSettings settings)
            throws Exception {
        session.update(world, view, settings.withRestartRevision(2).withPaused(true));
        long generation = session.progress(null).requestedGeneration();
        session.request();
        Thread.sleep(60);
        require(!session.progress(null).running(), "paused session does not start tracing");
        session.suspend();
        session.update(world, view, settings.withRestartRevision(2));
        try (var image = await(session, 4)) {
            require(image.generation() >= generation, "resume obtains current image");
        }
    }

    private static void controls(String backend) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery(), backend)) {
            var capabilities = renderer.capabilities();
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> {
                        var panel = new GameSettingsPanel(navigation, renderer);
                        var depth = (javax.swing.JSpinner) find(panel, "Path depth");
                        require(
                                ((Number)
                                                        ((javax.swing.SpinnerNumberModel)
                                                                        depth.getModel())
                                                                .getMaximum())
                                                .intValue()
                                        == capabilities.maximumDepth(),
                                "UI uses capability depth limit");
                        var adaptive = find(panel, "Adaptive resolution");
                        var support =
                                capabilities.support(
                                        RenderCapabilities.Feature.ADAPTIVE_RESOLUTION);
                        require(adaptive.isEnabled() == support.supported(), "UI support state");
                        if (!support.supported()) {
                            require(
                                    adaptive.getToolTipText().equals(support.reason()),
                                    "UI capability reason");
                            var before = renderer.settings();
                            try {
                                renderer.configure(s -> s.withPathDepth(3));
                                throw new AssertionError("unsupported edit accepted");
                            } catch (RenderCapabilities.UnsupportedInput expected) {
                                require(
                                        renderer.settings().equals(before),
                                        "rejected game edit preserves state");
                            }
                        }
                    });
        }
    }

    private static javax.swing.JComponent find(java.awt.Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return (javax.swing.JComponent) child;
            }
            if (child instanceof java.awt.Container container) {
                var found = find(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
