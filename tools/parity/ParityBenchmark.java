package game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import math.Vec3;

/** Fixed 480x300 world route: six seconds moving, two stationary, no native window. */
public final class ParityBenchmark {
    private ParityBenchmark() {}

    public static void main(String[] args) throws Exception {
        String backend = args[0];
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        StringBuilder summary = new StringBuilder("backend,run,initMs,medianIntervalMs,p95IntervalMs,p95AgeMs,peakHeapBytes,traceAllocatedBytes,finalSpp\n");
        for (int run = 0; run < 4; run++) {
            var navigation = new GameNavigation();
            navigation.fixedResolution(480, 300);
            var world = AdventureWorld.create();
            long start = System.nanoTime();
            try (var renderer = new GameRenderer(navigation, world, backend)) {
                renderer.adaptive(false);
                var intervals = new ArrayList<Double>();
                var ages = new ArrayList<Double>();
                var rows = new StringBuilder("seconds,generation,spp,intervalMs,ageMs,traceAllocatedBytes\n");
                long[] last = {0}, allocated = {0};
                double[] initialized = {-1};
                renderer.observePublications(frame -> {
                    double elapsed = (frame.publicationNanos() - start) / 1e9;
                    if (initialized[0] < 0) initialized[0] = elapsed * 1000;
                    double interval = last[0] == 0 ? 0 : (frame.publicationNanos() - last[0]) / 1e6;
                    if (last[0] != 0 && elapsed < 6) intervals.add(interval);
                    if (elapsed < 6) ages.add(frame.metrics().ageNanos() / 1e6);
                    last[0] = frame.publicationNanos();
                    long bytes = frame.metrics().trace().allocatedBytes();
                    if (bytes >= 0) allocated[0] += bytes;
                    rows.append(String.format(Locale.ROOT, "%.6f,%d,%d,%.6f,%.6f,%d%n", elapsed,
                            frame.generation(), frame.samples(), interval, frame.metrics().ageNanos()/1e6, bytes));
                });
                long peakHeap = 0;
                while ((System.nanoTime() - start) / 1e9 < 8) {
                    double time = Math.min(6, (System.nanoTime() - start) / 1e9);
                    navigation.pose(new Vec3((float) Math.sin(time) * 2, 2.4f, (float) (-6 + time)),
                            Math.sin(time * .7) * .15, -.14);
                    peakHeap = Math.max(peakHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                    if (renderer.error() != null) throw new AssertionError(renderer.error());
                    Thread.sleep(5);
                }
                renderer.observePublications(null);
                if (renderer.frame() == null || intervals.isEmpty()) throw new AssertionError("No route publications");
                summary.append(String.format(Locale.ROOT,"%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d%n",
                        backend, run, initialized[0], quantile(intervals,.5),quantile(intervals,.95),quantile(ages,.95),
                        peakHeap, allocated[0], renderer.frame().samples()));
                Files.writeString(output.resolve(backend + "-run-" + run + ".csv"), rows);
                Files.writeString(output.resolve(backend + "-runtime.txt"), "JVM=" + System.getProperty("java.runtime.version")
                        + "\nOS=" + System.getProperty("os.name") + "\nCPU=" + System.getenv("PROCESSOR_IDENTIFIER")
                        + "\nsettings=" + renderer.settings() + "\nstatus=" + renderer.backendStatus()
                        + "\nrun0=warmup; trace allocations are reported only where supported, heap is sampled live usage, not reserved/device memory\n");
            }
        }
        Files.writeString(output.resolve(backend + "-summary.csv"), summary);
        System.out.print(summary);
    }

    private static double quantile(ArrayList<Double> values, double fraction) {
        Collections.sort(values);
        return values.get(Math.min(values.size()-1, (int) Math.ceil(values.size()*fraction)-1));
    }
}
