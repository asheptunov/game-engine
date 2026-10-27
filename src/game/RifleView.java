package game;

import engine.Camera;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.RenderView;
import engine.SceneDocument;
import engine.SceneInstance;
import engine.Sky;
import engine.SpatialQuery;
import engine.Transform;
import engine.WorldSnapshot;
import engine.lights.DirectionalLight;
import engine.objects.Rect;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One coalescing owner ray traces the view model; Swing only requests and displays owned images.
 */
final class RifleView implements AutoCloseable {
    private record Request(
            int width, int height, int phase, DirectionalLight sun, Sky sky, float exposure) {}

    private final BufferedImage[] poses = new BufferedImage[13];
    private final boolean[][] silhouettes = new boolean[13][];
    private Request cached;
    private volatile BufferedImage displayed;
    private volatile String error;
    private volatile boolean closed;
    private final AtomicReference<Request> wanted = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    task -> {
                        var thread = new Thread(task, "rifle-ray-tracing");
                        thread.setDaemon(true);
                        return thread;
                    });

    BufferedImage request(
            int width,
            int height,
            double cooldown,
            Camera camera,
            GameLighting lighting,
            float exposure) {
        if (closed) {
            return displayed;
        }
        var right = camera.sensor().edge1().normalized();
        var up = camera.sensor().edge2().normalized();
        var direction = lighting.sun().direction();
        var local =
                new Vec3(direction.dot(right), direction.dot(up), direction.dot(camera.forward()));
        var sun = new DirectionalLight(local, lighting.sun().color(), lighting.sun().strength());
        wanted.set(
                new Request(
                        width,
                        height,
                        phase(cooldown),
                        sun,
                        new Sky(lighting.sky().nadir(), lighting.sky().zenith()),
                        exposure));
        schedule();
        return displayed;
    }

    private void schedule() {
        if (closed || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            running.set(false);
        }
    }

    private void drain() {
        try {
            Request request;
            while (!closed && (request = wanted.getAndSet(null)) != null) {
                displayed = render(request);
            }
        } catch (RuntimeException failure) {
            if (!closed) {
                error = "Rifle ray tracing: " + failure.getMessage();
            }
        } finally {
            running.set(false);
            if (wanted.get() != null && !closed) {
                schedule();
            }
        }
    }

    String error() {
        return error;
    }

    /** Blocking deterministic entry point for offscreen verification, never called by Swing. */
    synchronized BufferedImage image(int width, int height, double cooldown) {
        var light = GameLighting.defaults();
        return render(new Request(width, height, phase(cooldown), light.sun(), light.sky(), 1));
    }

    private static int phase(double cooldown) {
        return (int) Math.round(Math.sin(Math.PI * Math.clamp(cooldown / 1.25, 0, 1)) * 12);
    }

    private BufferedImage render(Request request) {
        if (cached == null || cached.width != request.width || cached.height != request.height) {
            Arrays.fill(silhouettes, null);
        }
        if (cached == null || !sameLightingAndGrid(cached, request)) {
            Arrays.fill(poses, null);
        }
        cached = request;
        if (poses[request.phase] != null) {
            return poses[request.phase];
        }
        var pose =
                new Transform(
                        new Vec3(.24f, -.245f - request.phase * .0015f, .85f),
                        new Vec3(0, -10, -3),
                        new Vec3(1, 1, 1));
        double cycle = Math.asin(request.phase / 12.) / Math.PI * 1.25;
        var parts = RifleMesh.instances("view/", pose, cycle);
        float height = (float) (2 * Math.tan(Math.toRadians(30)));
        float width = height * request.width / request.height;
        var camera =
                new Camera(
                        Vec3.ZERO,
                        new Rect(
                                new Vec3(-width / 2, -height / 2, 1),
                                new Vec3(width, 0, 0),
                                new Vec3(0, height, 0)));
        if (silhouettes[request.phase] == null) {
            silhouettes[request.phase] = silhouette(parts, camera, request.width, request.height);
        }
        var world = new WorldSnapshot(0, parts, List.of(), List.of(request.sun), request.sky);
        return trace(request, world, camera);
    }

    private BufferedImage trace(Request request, WorldSnapshot world, Camera camera) {
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withWorkers(Math.min(2, Runtime.getRuntime().availableProcessors()))
                        .withSamplesPerBatch(4)
                        .withSampleTarget(4);
        try (var session =
                RenderEngine.openSession(
                        world, new RenderView(camera, request.width, request.height), settings)) {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!closed && System.nanoTime() < deadline) {
                session.request();
                if (session.failure() != null) {
                    throw new IllegalStateException(session.failure().toString());
                }
                try (var lease = session.acquireImage()) {
                    if (lease != null && lease.samples() >= 4) {
                        var rgb = GameRenderer.copyImage(lease, request.exposure);
                        var output = masked(rgb, silhouettes[request.phase]);
                        poses[request.phase] = output;
                        return output;
                    }
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("Rifle renderer stopped");
                }
            }
            throw new IllegalStateException("Rifle render timed out or closed");
        }
    }

    private static BufferedImage masked(BufferedImage rgb, boolean[] mask) {
        var output =
                new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rgb.getHeight(); y++) {
            for (int x = 0; x < rgb.getWidth(); x++) {
                if (mask[y * rgb.getWidth() + x]) {
                    output.setRGB(x, y, rgb.getRGB(x, y));
                }
            }
        }
        return output;
    }

    private static boolean sameLightingAndGrid(Request a, Request b) {
        return a.width == b.width
                && a.height == b.height
                && a.sun.equals(b.sun)
                && a.sky.equals(b.sky)
                && a.exposure == b.exposure;
    }

    private boolean[] silhouette(List<SceneInstance> parts, Camera camera, int width, int height) {
        var document = new SceneDocument();
        var query =
                SpatialQuery.prepare(
                        document.transact(
                                edit -> {
                                    for (var part : parts) {
                                        var node =
                                                edit.createNode(
                                                        part.name(), null, part.transform());
                                        edit.assignGeometry(
                                                node,
                                                edit.createGeometry(part.name(), part.geometry()),
                                                edit.createMaterial(part.name(), part.material()));
                                    }
                                }));
        var mask = new boolean[width * height];
        for (int y = 0; y < height && !closed; y++) {
            for (int x = 0; x < width; x++) {
                mask[y * width + x] =
                        query.pick(camera, (x + .5f) / width, 1 - (y + .5f) / height).isPresent();
            }
        }
        return mask;
    }

    @Override
    public void close() {
        closed = true;
        wanted.set(null);
        executor.shutdownNow();
    }
}
