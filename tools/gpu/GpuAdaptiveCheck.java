package gpu;

import engine.*;

import game.GameNavigation;

import math.Vec3;

/** Synthetic policy checks plus real-device grid transitions, exact samples and held leases. */
public final class GpuAdaptiveCheck {
    public static void main(String[] args) throws Exception {
        policy();
        lifecycle(false);
        lifecycle(true);
        System.out.println(
                "PASS GPU adaptive policy, restoration, pause, toggle, exact pixels and leases");
    }

    private static RenderView view(int step, int width, int height) {
        var navigation = new GameNavigation();
        navigation.fixedResolution(width, height);
        navigation.pose(new Vec3(step * .01f, 2.4f, -6), .1, -.14);
        var result = navigation.view();
        return new RenderView(result.camera().withAperture(.01f), width, height);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void policy() {
        var policy = new GpuResolutionPolicy();
        var settings = RenderSettings.defaults().withInteractive(true, 1000. / 120, 0, 0);
        long now = 1_000_000_000L;
        policy.observe(view(0, 1920, 1080), now);
        require(
                policy.choose(view(0, 1920, 1080), settings, now).width() == 1920,
                "initial stationary");
        policy.completed(1920, 1080, 30_000_000);
        policy.observe(view(1, 1920, 1080), now);
        var reduced = policy.choose(view(1, 1920, 1080), settings, now);
        require(reduced.width() < 1920 && reduced.width() >= 480, "bounded reduction");
        for (int i = 1; i < 50; i++) {
            policy.observe(view(i + 1, 1920, 1080), now + i * 5_000_000L);
            require(
                    policy.choose(view(i + 1, 1920, 1080), settings, now + i * 5_000_000L).width()
                            == reduced.width(),
                    "cooldown prevents grid thrash");
        }
        require(
                policy.choose(view(50, 1920, 1080), settings, now + 700_000_000L).width() == 1920,
                "quiet restores requested");
        policy.observe(view(51, 1920, 1080), now + 800_000_000L);
        var minimum = settings.withInteractive(true, 1, 1280, 720);
        require(
                policy.choose(view(51, 1920, 1080), minimum, now + 800_000_000L).width() >= 1280,
                "custom minimum enforced immediately");
        require(
                policy.choose(view(51, 1920, 1080), RenderSettings.defaults(), now + 800_000_000L)
                                .width()
                        == 1920,
                "disabled restores full");
        policy.observe(view(52, 64, 1920), now + 900_000_000L);
        policy.observe(view(53, 64, 1920), now + 910_000_000L);
        require(
                policy.choose(view(53, 64, 1920), settings, now + 910_000_000L).width() == 64,
                "thin aspect respects 64 pixel floor");
    }

    private static void lifecycle(boolean resident) throws Exception {
        var world = GpuCheck.landscape();
        var settings = RenderSettings.defaults().withPathDepth(3).withInteractive(true, 1, 0, 0);
        var session = new GpuRenderSession(world, view(0, 640, 360), settings);
        session.displayExposure(1);
        session.devicePresentation(resident);
        RenderImage held = null;
        RenderView captured = null;
        float[] pixels = null;
        try {
            try (var initial = await(session, image -> image.width() == 640)) {
                session.presented(initial);
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            int step = 1;
            while (System.nanoTime() < deadline && held == null) {
                session.update(world, view(step++, 640, 360), settings);
                session.request();
                var image = session.acquireImage();
                if (image != null) {
                    session.presented(image);
                    if (image.width() < 640) held = image;
                    else image.close();
                }
                Thread.sleep(5);
            }
            require(held != null, "motion publishes reduced image");
            captured = new RenderView(held.camera(), held.width(), held.height());
            pixels = read(held);
            require(held.samples() == 1, "moving cap is one sample");
            Thread.sleep(400);
            try (var full =
                    await(
                            session,
                            image ->
                                    image.width() == 640
                                            && image.generation()
                                                    == session.progress(null)
                                                            .requestedGeneration())) {
                require(full.height() == 360, "stationary aspect restored");
            }
            require(
                    java.util.Arrays.equals(pixels, read(held)),
                    "held reduced pixels survived resize");
            var paused = settings.withPaused(true);
            session.update(world, view(step++, 640, 360), paused);
            session.request();
            require(!session.progress(null).running(), "pause submits no work");
            session.update(world, view(step++, 640, 360), settings.withInteractive(false, 1, 0, 0));
            try (var full =
                    await(
                            session,
                            image ->
                                    image.width() == 640
                                            && image.generation()
                                                    == session.progress(null)
                                                            .requestedGeneration())) {
                require(full.samples() == 1, "toggle retains target");
            }
            session.suspend();
            session.update(world, view(step, 800, 400), settings);
            try (var resized = await(session, image -> image.width() == 800)) {
                require(resized.height() == 400, "resume and requested resize");
            }
        } finally {
            session.close();
            require(session.awaitClosed(30), "owner retired");
        }
        require(java.util.Arrays.equals(pixels, read(held)), "lease survives close");
        held.close();
        try (var kernel = new GpuKernel()) {
            var expected = new float[pixels.length];
            kernel.trace(world, captured, settings, 0, expected);
            require(
                    java.util.Arrays.equals(pixels, expected),
                    "exact captured camera at actual grid");
        }
    }

    private static RenderImage await(
            GpuRenderSession session, java.util.function.Predicate<RenderImage> ready)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (ready.test(image)) return image;
                image.close();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No expected image: " + session.status());
    }

    private static float[] read(RenderImage image) {
        var values = new float[image.width() * image.height() * 3];
        for (int y = 0; y < image.height(); y++)
            for (int x = 0; x < image.width(); x++)
                for (int c = 0; c < 3; c++)
                    values[(y * image.width() + x) * 3 + c] = image.rawPixels().value(c, x, y);
        return values;
    }
}
