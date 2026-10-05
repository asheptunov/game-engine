package profiling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Render-thread-owned, exclusive stage timings; UI flags and JFR samples cross threads. */
public final class FrameProfiler implements AutoCloseable {
    public enum Stage { BACKGROUND, SCENE, TRACE, RESAMPLE, PAINT, OVERLAY, PRESENT, OTHER, IDLE }
    public record Rays(int width, int height, int primary, int hits, int shadows, int occluded,
                       int lit, long traceNanos) {}
    public record Frame(long start, long end, List<Long> nanos, Rays rays, TraceProfile.Stats trace) {
        public long duration() { return end - start; }
        public long stage(Stage stage) { return nanos.get(stage.ordinal()); }
    }
    public record Snapshot(List<Frame> frames, double fps, double meanMs, double p95Ms) {}
    private static final long WINDOW = 10_000_000_000L;
    private static final int CAPACITY = 4096;
    private final LongSupplier clock;
    private final Deque<Frame> history = new ArrayDeque<>();
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private long[] times;
    private long start, renderEnd;
    private boolean pending;
    private Rays rays;
    private TraceProfile.Stats trace;
    private volatile boolean visible;
    private volatile boolean traceDetails;
    private final TraceSampler traceSampler;
    private static final class Scope { long children; }

    public FrameProfiler() { this(System::nanoTime); }
    public FrameProfiler(LongSupplier clock) { this(clock, new TraceSampler()); }
    FrameProfiler(LongSupplier clock, TraceSampler sampler) { this.clock = clock; this.traceSampler = sampler; }
    @Override public void close() { traceSampler.close(); }
    public boolean visible() { return visible; }
    public void toggle() { visible = !visible; }
    public boolean traceDetails() { return traceDetails; }
    public void toggleTraceDetails() { traceDetails = !traceDetails; visible = true; }
    public TraceSampler.Snapshot traceSamples() { return traceSampler.snapshot(); }
    public long now() { return clock.getAsLong(); }

    public void beginFrame() {
        traceSampler.enabled(traceDetails);
        long now = now();
        if (pending) {
            times[Stage.IDLE.ordinal()] = Math.max(0, now - renderEnd);
            long accounted = Arrays.stream(times).sum();
            times[Stage.OTHER.ordinal()] += Math.max(0, now - start - accounted);
            history.addLast(new Frame(start, now, Arrays.stream(times).boxed().toList(), rays, trace));
        }
        while (!history.isEmpty() && (history.peekFirst().end() < now - WINDOW || history.size() > CAPACITY)) {
            history.removeFirst();
        }
        start = now;
        times = new long[Stage.values().length];
        rays = null;
        trace = null;
        scopes.clear();
        pending = false;
    }
    public void endFrame() { renderEnd = now(); pending = true; }
    public void rayStats(Rays stats) { rays = stats; }
    public void traceStats(TraceProfile.Stats stats) { trace = stats; }
    public void measure(Stage stage, Runnable action) {
        measure(stage, () -> { action.run(); return null; });
    }
    public <T> T measure(Stage stage, Supplier<T> action) {
        if (times == null) return action.get();
        var scope = new Scope();
        scopes.push(scope);
        long before = now();
        try { return action.get(); }
        finally {
            long elapsed = Math.max(0, now() - before);
            scopes.pop();
            times[stage.ordinal()] += Math.max(0, elapsed - scope.children);
            if (!scopes.isEmpty()) scopes.peek().children += elapsed;
        }
    }
    public Snapshot snapshot() {
        var frames = new ArrayList<>(history);
        if (frames.isEmpty()) return new Snapshot(List.of(), 0, 0, 0);
        double[] ms = frames.stream().mapToDouble(f -> f.duration() / 1e6).sorted().toArray();
        double mean = Arrays.stream(ms).average().orElse(0);
        return new Snapshot(List.copyOf(frames), mean > 0 ? 1000 / mean : 0,
                mean, ms[(int) Math.ceil(ms.length * .95) - 1]);
    }
    /** Latest published frame without allocating a history snapshot. */
    public Frame latestFrame() { return history.peekLast(); }
}
