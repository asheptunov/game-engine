package game;

import gpu.GpuRenderSession;

import math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/** Alternating matched game routes, including GPU readback and actual game image conversion. */
public final class CompactDisplayBenchmark {
    private CompactDisplayBenchmark() {}

    public static void main(String[] args) throws Exception {

        System.out.println(
                "round,conversion,imagesPerSecond,medianIntervalMs,p95IntervalMs,p95AgeMs,p99IntervalMs,over16msPercent,medianCopyMs,p95CopyMs");
        for (int round = 0; round < 3; round++) {
            measure(round, round % 2 == 0);
            measure(round, round % 2 != 0);
        }
    }

    private static void measure(int round, boolean bulk) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(1920, 1080);
        System.setProperty("game.referenceDisplay", Boolean.toString(!bulk));
        var session = new GpuRenderSession[1];
        var hooks =
                new GameRenderer.RuntimeHooks(
                        new GameDiagnostics(),
                        failure -> {},
                        (world, view, settings, backend) -> {
                            session[0] = new GpuRenderSession(world, view, settings);
                            return session[0];
                        });
        var intervals = new ArrayList<Double>();
        var ages = new ArrayList<Double>();
        var copies = new ArrayList<Double>();
        long[] last = {0};
        long[] count = {0};
        try (var renderer =
                new GameRenderer(
                        navigation,
                        BlockWorld.landscape().snapshot(),
                        "gpu",
                        GameRenderer::copyImage,
                        hooks)) {
            // Warm each live renderer, then replay the same three-second movement interval.
            long warm = System.nanoTime();
            while (System.nanoTime() - warm < 2_000_000_000L || renderer.frame() == null) {
                if (System.nanoTime() - warm > 30_000_000_000L)
                    throw new AssertionError("Warmup timeout");
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            long start = System.nanoTime();
            renderer.observePublications(
                    frame -> {
                        long now = frame.publicationNanos();
                        if (now - start >= 3_000_000_000L) return;
                        if (last[0] != 0) intervals.add((now - last[0]) / 1e6);
                        last[0] = now;
                        count[0]++;
                        ages.add(frame.metrics().ageNanos() / 1e6);
                        copies.add(frame.metrics().copyNanos() / 1e6);
                    });
            while (System.nanoTime() - start < 3_000_000_000L) {
                double time = (System.nanoTime() - start) / 1e9;
                navigation.pose(
                        new Vec3((float) Math.sin(time) * 2, 2.4f, (float) (-6 + time)),
                        Math.sin(time * .7) * .15,
                        -.14);
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            renderer.observePublications(null);
            if (intervals.isEmpty()) throw new AssertionError("No route publications");
            System.out.printf(
                    Locale.ROOT,
                    "%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    round,
                    bulk ? "gpu-packed" : "cpu-parallel",
                    count[0] / 3.,
                    quantile(intervals, .5),
                    quantile(intervals, .95),
                    quantile(ages, .95),
                    quantile(intervals, .99),
                    100.0 * intervals.stream().filter(value -> value > 16.67).count() / intervals.size(),
                    quantile(copies, .5),
                    quantile(copies, .95));
        } finally {
            if (session[0] != null && !session[0].awaitClosed(30)) {
                throw new AssertionError("GPU owner did not retire");
            }
        }
    }

    private static double quantile(ArrayList<Double> values, double fraction) {
        Collections.sort(values);
        return values.get(
                Math.min(values.size() - 1, (int) Math.ceil(values.size() * fraction) - 1));
    }
}
