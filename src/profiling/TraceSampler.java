package profiling;

import jdk.jfr.consumer.RecordingStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/** Opt-in JFR execution sampling: no clocks, counters, or callbacks in the per-ray loop. */
public final class TraceSampler implements AutoCloseable {
    public enum Work { GENERATION, INTERSECTION, LIGHTING, SHADOW }
    public record Snapshot(long generation, long intersection, long lighting, long shadow, String status) {
        public long total() { return generation + intersection + lighting + shadow; }
    }
    private record Sample(long time, Work work) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private RecordingStream stream;
    private volatile String status = "off";
    private boolean enabled;

    public void enabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (!value) { close(); return; }
        synchronized (samples) { samples.clear(); }
        try {
            stream = new RecordingStream();
            stream.setMaxAge(Duration.ofSeconds(15));
            stream.setMaxSize(16 * 1024 * 1024);
            stream.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
            stream.onEvent("jdk.ExecutionSample", event -> {
                if (event.getStackTrace() == null) return;
                var methods = event.getStackTrace().getFrames().stream()
                        .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName()).toList();
                Work work = classify(methods);
                if (work != null) {
                    long age = Math.max(0, Duration.between(event.getStartTime(), Instant.now()).toNanos());
                    record(System.nanoTime() - age, work);
                }
            });
            stream.onError(error -> status = "unavailable: " + error.getClass().getSimpleName());
            status = "warming up (JFR events arrive in batches)";
            stream.startAsync();
        } catch (RuntimeException error) {
            close();
            status = "unavailable: " + error.getClass().getSimpleName();
        }
    }

    static Work classify(List<String> methods) {
        String tracer = methods.contains("scenes.viewport.DirectRgbTracer.trace")
                ? "scenes.viewport.DirectRgbTracer." : "scenes.viewport.BackwardRayTracer.";
        if (!methods.contains(tracer + "trace")) return null;
        if (methods.contains(tracer + "occluded")) return Work.SHADOW;
        if (methods.contains(tracer + "nearestHit")) return Work.INTERSECTION;
        if (methods.contains(tracer + "light") || methods.contains(tracer + "shade")) return Work.LIGHTING;
        return Work.GENERATION;
    }

    void record(long now, Work work) {
        synchronized (samples) {
            samples.addLast(new Sample(now, work));
            prune(now);
        }
        status = "active";
    }

    public Snapshot snapshot() { return snapshot(System.nanoTime()); }
    Snapshot snapshot(long now) {
        long[] counts = new long[Work.values().length];
        synchronized (samples) {
            prune(now);
            for (var sample : samples) counts[sample.work().ordinal()]++;
        }
        return new Snapshot(counts[0], counts[1], counts[2], counts[3], status);
    }
    private void prune(long now) {
        while (!samples.isEmpty() && (samples.peekFirst().time() < now - 10_000_000_000L || samples.size() > 4096)) samples.removeFirst();
    }
    @Override public void close() {
        if (stream != null) { stream.close(); stream = null; }
        synchronized (samples) { samples.clear(); }
        status = "off";
    }
}
