package game;

import engine.RenderEngine;
import engine.RenderSettings;
import engine.RenderView;
import engine.WorldSnapshot;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** CPU checks for the game owner and equal-work worker/tile measurements without a window. */
public final class GameSchedulingBenchmark {
    private GameSchedulingBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("cpu")) {
            cpu();
        } else {
            sweep();
        }
    }

    private static void cpu() throws Exception {
        var route = CameraRoute.load(java.nio.file.Path.of("benchmarks/game/camera-route.csv"));
        var navigation = new GameNavigation();
        try (var renderer = new GameRenderer(navigation, BlockWorld.landscape())) {
            renderer.adaptive(false);
            System.out.println("phase,seconds,processCpuSeconds,ownerCpuSeconds,machineCpuPercent");
            // Warm tracing and conversion before measuring active work or suspension.
            phase(renderer, navigation, route, "warmup", 5, true);
            phase(renderer, navigation, route, "moving", 10, true);
            navigation.active(false);
            Thread.sleep(500);
            phase(renderer, navigation, route, "suspended", 5, false);
            navigation.active(true);
            phase(renderer, navigation, route, "resumed", 5, true);
        }
    }

    private static void phase(
            GameRenderer renderer,
            GameNavigation navigation,
            CameraRoute route,
            String name,
            int seconds,
            boolean moving)
            throws Exception {
        var os =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
        var threads = ManagementFactory.getThreadMXBean();
        if (threads.isThreadCpuTimeSupported()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        long owner =
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().equals("sample-game-update"))
                        .mapToLong(Thread::threadId)
                        .findFirst()
                        .orElse(-1);
        long ownerStart = owner < 0 ? -1 : threads.getThreadCpuTime(owner);
        long cpuStart = os.getProcessCpuTime();
        long start = System.nanoTime();
        while (System.nanoTime() - start < seconds * 1_000_000_000L) {
            if (moving) {
                route.apply(navigation, (System.nanoTime() - start) / 1e9);
            }
            if (renderer.error() != null) {
                throw new IllegalStateException(renderer.error());
            }
            Thread.sleep(2);
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        double cpu = (os.getProcessCpuTime() - cpuStart) / 1e9;
        double ownerCpu =
                ownerStart < 0 ? -1 : (threads.getThreadCpuTime(owner) - ownerStart) / 1e9;
        System.out.printf(
                Locale.ROOT,
                "%s,%.3f,%.6f,%.6f,%.3f%n",
                name,
                elapsed,
                cpu,
                ownerCpu,
                100 * cpu / elapsed / os.getAvailableProcessors());
    }

    private static void sweep() throws Exception {
        var prepared = BlockWorld.landscape().snapshot();
        var light = GameLighting.defaults();
        var world =
                new WorldSnapshot(
                        0,
                        prepared.instances(),
                        prepared.legacyObjects(),
                        List.of(light.sun()),
                        light.sky());
        var camera = new GameNavigation().view().camera();
        System.out.println("round,width,height,workers,tile,medianTraceMs,p95TraceMs");
        for (int round = 0; round < 3; round++) {
            for (int width : new int[] {102, 320}) {
                int height = width == 102 ? 64 : 200;
                for (int index = 0; index < 12; index++) {
                    int configuration = round % 2 == 0 ? index : 11 - index;
                    int workers = new int[] {1, 4, 8, 14}[configuration / 3];
                    int tile = new int[] {8, 16, 32}[configuration % 3];
                    measure(world, new RenderView(camera, width, height), round, workers, tile);
                }
            }
        }
    }

    private static void measure(
            WorldSnapshot world, RenderView view, int round, int workers, int tile)
            throws Exception {
        if (workers > Runtime.getRuntime().availableProcessors()) {
            return;
        }
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withWorkers(workers)
                        .withTileSize(tile)
                        .withSampleTarget(1);
        var signal = new Semaphore(0);
        var times = new double[40];
        try (var session = RenderEngine.openSession(world, view, settings)) {
            session.onImageAvailable(signal::release);
            for (int pass = 0; pass < 60; pass++) {
                session.update(world, view, settings.withRestartRevision(pass));
                session.request();
                if (!signal.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("Missing publication");
                }
                try (var image = session.acquireImage()) {
                    if (image.samples() != 1) {
                        throw new AssertionError("Unequal work");
                    }
                    if (pass >= 20) {
                        times[pass - 20] = image.traceNanos() / 1e6;
                    }
                }
            }
        }
        Arrays.sort(times);
        System.out.printf(
                Locale.ROOT,
                "%d,%d,%d,%d,%d,%.6f,%.6f%n",
                round,
                view.width(),
                view.height(),
                workers,
                tile,
                (times[19] + times[20]) / 2,
                times[37]);
    }
}
