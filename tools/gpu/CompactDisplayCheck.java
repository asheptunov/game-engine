package game;

import engine.*;
import gpu.GpuRenderSession;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Headless real-device checks for packed presentation and lazy raw snapshot ownership. */
public final class CompactDisplayCheck {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("resident-captures")) {
            checkCaptures(true);
            return;
        }
        int width = args.length == 0 ? 1920 : Integer.parseInt(args[0]);
        int height = args.length < 2 ? 1080 : Integer.parseInt(args[1]);
        var nav = new GameNavigation();
        nav.fixedResolution(width, height);
        var source = BlockWorld.landscape().snapshot();
        var lighting = GameLighting.defaults();
        var world = new WorldSnapshot(0, source.instances(), source.legacyObjects(),
                java.util.List.of(lighting.sun()), lighting.sky());
        var settings = RenderSettings.defaults().withPathDepth(0).withSampleTarget(1)
                .withInteractive(false, 16.67, 0, 0);
        var ready = new Semaphore(0);
        var session = new GpuRenderSession(world, nav.view(), settings);
        try {
            session.onImageAvailable(ready::release);
            session.displayExposure(1);
            session.request();
            waitFor(ready, session);
            try (var held = session.acquireImage()) {
                var first = packed(held, 1);
                require(session.status().contains("GPU packed"), "compact path selected");
                require(held.samples() == 1, "sample count");
                // Read the raw snapshot only after newer work has overwritten kernel output.
                session.update(world, nav.view(), settings.withRestartRevision(1));
                session.request();
                waitFor(ready, session);
                require(Arrays.equals(first, packed(held, 1)), "held packed image unchanged");
                exactReference(held, first, 1);
                for (float exposure : new float[] {0, .125f, 4, 100}) {
                    exactReference(held, packed(held, exposure), exposure);
                }
                try (var second = session.acquireImage()) {
                    require(Arrays.equals(first, packed(second, 1)), "restart reproduces packed image");
                    nav.fixedResolution(320, 180);
                    session.displayExposure(.125f);
                    session.update(world, nav.view(), settings.withRestartRevision(2));
                    session.request();
                    waitFor(ready, session);
                    try (var resized = session.acquireImage()) {
                        require(resized.width() == 320 && resized.height() == 180, "resize");
                        var encoded = packed(resized, .125f);
                        // All three publication slots have readers; no overwrite is allowed.
                        session.update(world, nav.view(), settings.withRestartRevision(3));
                        session.request();
                        require(!ready.tryAcquire(100, TimeUnit.MILLISECONDS), "bounded publication slots");
                        session.close();
                        require(session.awaitClosed(30), "owner retires");
                        exactReference(resized, encoded, .125f);
                        exactReference(second, first, 1);
                    }
                }
            }
        } finally {
            session.close();
            require(session.awaitClosed(30), "cleanup");
        }
        checkCaptures(false);
        System.out.println("PASS compact GPU display " + width + "x" + height
                + ": pixels, exposure, raw snapshots, resize, three-slot leases, close");
    }

    private static void checkCaptures(boolean resident) throws Exception {
        var nav = new GameNavigation();
        nav.fixedResolution(320, 180);
        var session = new GpuRenderSession[1];
        var hooks = new GameRenderer.RuntimeHooks(new GameDiagnostics(), failure -> {},
                (world, view, settings, backend) -> {
                    session[0] = new GpuRenderSession(world, view, settings);
                    return session[0];
                });
        try (var renderer = new GameRenderer(nav, BlockWorld.landscape().snapshot(), "gpu",
                GameRenderer::copyImage, hooks)) {
            if (resident) renderer.devicePresentation();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((renderer.frame() == null || (resident && renderer.frame().devicePixels() == null))
                    && System.nanoTime() < deadline) {
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            var frame = renderer.acquireFrame();
            require(frame != null, "capture frame");
            try {
                renderer.displayed(frame);
                var root = java.nio.file.Path.of("out/gpu/compact-captures");
                for (boolean history : new boolean[] {false, true}) {
                    var directory = renderer.captureBundle(history, root).get(30, TimeUnit.SECONDS);
                    GameCapture.load(directory);
                    var png = javax.imageio.ImageIO.read(directory.resolve("displayed.png").toFile());
                    require(Arrays.equals(frame.image().getRGB(0, 0, 320, 180, null, 0, 320),
                            png.getRGB(0, 0, 320, 180, null, 0, 320)), "capture displayed bytes");
                }
                System.out.println("PASS GPU snapshot/history captures and reload, resident=" + resident);
            } finally {
                frame.release();
            }
        } finally {
            if (session[0] != null) require(session[0].awaitClosed(30), "capture owner retires");
        }
    }

    static int[] packed(RenderImage image, float exposure) {
        int[] result = new int[image.width() * image.height()];
        image.presentationPixels().copyDisplayTo(result, exposure);
        return result;
    }

    private static void exactReference(RenderImage image, int[] actual, float exposure) {
        var raw = image.rawPixels();
        int different = 0;
        int maxError = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int expected = DisplayMapping.pack(raw.value(0, x, y), raw.value(1, x, y),
                        raw.value(2, x, y), exposure);
                int value = actual[(image.height() - 1 - y) * image.width() + x];
                if (value != expected) different++;
                for (int shift : new int[] {0, 8, 16}) {
                    maxError = Math.max(maxError, Math.abs(((value >>> shift) & 255) - ((expected >>> shift) & 255)));
                }
            }
        }
        require(different == 0, "display bytes differ: " + different + "; max error=" + maxError);
        System.out.println("exposure=" + exposure + " differingPixels=" + different + " maxChannelError=" + maxError);
    }

    private static void waitFor(Semaphore ready, GpuRenderSession session) throws Exception {
        require(ready.tryAcquire(60, TimeUnit.SECONDS), "publication timeout");
        if (session.failure() != null) throw session.failure();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
