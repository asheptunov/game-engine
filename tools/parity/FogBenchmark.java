package game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import math.Vec3;

/** Fixed 480x300 world route: 24 seconds entering/leaving ruins fog, eight stationary, no native window. */
public final class FogBenchmark {
    private FogBenchmark() {}

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
                renderer.timeOfDay(5.6);
                renderer.atmospherePreset(false);
                renderer.fogPreset();
                renderer.timeOfDay(5.6);
                renderer.dayDuration(120);
                renderer.dayCycle(true);
                var intervals = new ArrayList<Double>();
                var ages = new ArrayList<Double>();
                var rows = new StringBuilder("seconds,generation,spp,intervalMs,ageMs,traceAllocatedBytes\n");
                long[] last = {0}, allocated = {0};
                double[] initialized = {-1};
                renderer.observePublications(frame -> {
                    double elapsed = (frame.publicationNanos() - start) / 1e9;
                    if (initialized[0] < 0) initialized[0] = elapsed * 1000;
                    double interval = last[0] == 0 ? 0 : (frame.publicationNanos() - last[0]) / 1e6;
                    if (last[0] != 0 && elapsed < 24) intervals.add(interval);
                    if (elapsed < 24) ages.add(frame.metrics().ageNanos() / 1e6);
                    last[0] = frame.publicationNanos();
                    long bytes = frame.metrics().trace().allocatedBytes();
                    if (bytes >= 0) allocated[0] += bytes;
                    rows.append(String.format(Locale.ROOT, "%.6f,%d,%d,%.6f,%.6f,%d%n", elapsed,
                            frame.generation(), frame.samples(), interval, frame.metrics().ageNanos()/1e6, bytes));
                });
                long peakHeap = 0;
                boolean sunset = false;
                boolean stationary = false;
                while ((System.nanoTime() - start) / 1e9 < 32) {
                    double time = Math.min(24, (System.nanoTime() - start) / 1e9);
                    if (time >= 12 && !sunset) {
                        renderer.timeOfDay(17.6);
                        sunset = true;
                    }
                    if (time >= 24 && !stationary) {
                        renderer.dayCycle(false);
                        stationary = true;
                    }
                    navigation.pose(new Vec3((float)(20 + 25*Math.cos(time*Math.PI/12)),4,18),
                            .85, .02);
                    peakHeap = Math.max(peakHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                    if (renderer.error() != null) throw new AssertionError(renderer.error());
                    Thread.sleep(5);
                }
                renderer.observePublications(null);
                if (renderer.frame() == null || intervals.size()<3) throw new AssertionError("No route publications");
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
        var table = engine.AtmosphereTable.get(engine.Atmosphere.Parameters.hazyMorning());
        Files.writeString(output.resolve(backend + "-table.txt"), "lookupPayloadBytes="+table.payloadBytes()+"\npreparationMs="+table.preparationNanos()/1e6+"\nviewSteps="+engine.AtmosphereTransport.VIEW_STEPS+"\nlookup="+engine.AtmosphereTable.HEIGHTS+"x"+engine.AtmosphereTable.DIRECTIONS+"\n");
        Files.writeString(output.resolve(backend + "-summary.csv"), summary);
        System.out.print(summary);
    }

    private static double quantile(ArrayList<Double> values, double fraction) {
        Collections.sort(values);
        return values.get(Math.min(values.size()-1, (int) Math.ceil(values.size()*fraction)-1));
    }
}
