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
    /** Async image diagnostics; excludes AWT delivery latency. */
    public record RenderProgress(long generation, long shownGeneration, long completedSamples,
                                 long imageAgeNanos, long firstImageNanos, long cancelledJobs,
                                 long wastedPrimaryRays, long maxTileNanos, boolean running) {}
    private RenderProgress renderProgress;
    private String viewportQuality;
    public void viewportQuality(String value) { viewportQuality=value; }
    public String viewportQuality() { return viewportQuality; }
    private String viewportHistory;
    public void viewportHistory(String value) { viewportHistory=value; }
    public String viewportHistory() { return viewportHistory; }
    public record ImageUpdate(long time, long intervalNanos, long ageNanos) {}
    public record ImageSnapshot(List<ImageUpdate> updates, double fps, double meanMs, double p95Ms,
                                double holdMs, double ageMs, double ageP95Ms, String status) {}
    private final Deque<ImageUpdate> imageHistory=new ArrayDeque<>();
    private long imageStart, lastImageTime, lastImageId=-1, capturedNanos=-1;
    private long candidateId=-1, candidateCapture=-1;
    private String imageContext, candidateContext, imageStatus="waiting";
    private boolean viewportFrame;
    /** Display-thread metadata only; the completed pipeline's endFrame timestamps visibility. */
    public void viewportImage(long id,long captured,int width,int height,String preset,String status) {
        viewportFrame=true; candidateId=id; candidateCapture=captured;
        candidateContext=preset+":"+width+"x"+height;
        imageStatus=status;
    }
    public void renderProgress(RenderProgress value) { renderProgress=value; }
    public RenderProgress renderProgress() { return renderProgress; }
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
        renderProgress = null;
        viewportQuality = null;
        viewportHistory = null;
        viewportFrame=false; candidateId=-1; candidateCapture=-1;
        scopes.clear();
        pending = false;
    }
    public void endFrame() {
        renderEnd = now(); pending = true;
        if(!viewportFrame) { resetImages(); return; }
        if(!candidateContext.equals(imageContext)) {
            imageHistory.clear(); imageStart=start;
            if(imageContext==null) lastImageTime=start;
            imageContext=candidateContext;
        }
        if(candidateId>=0 && candidateId!=lastImageId) {
            imageHistory.addLast(new ImageUpdate(renderEnd,Math.max(0,renderEnd-lastImageTime),
                    Math.max(0,renderEnd-candidateCapture)));
            lastImageTime=renderEnd; lastImageId=candidateId; capturedNanos=candidateCapture;
        }
        while(!imageHistory.isEmpty() && (imageHistory.peekFirst().time()<renderEnd-WINDOW || imageHistory.size()>CAPACITY))
            imageHistory.removeFirst();
    }
    private void resetImages() {
        imageHistory.clear(); imageContext=null; lastImageId=-1; capturedNanos=-1;
    }
    /** Two-second headline responds to resolution changes and includes the ongoing held image. */
    public ImageSnapshot imageSnapshot() {
        long now=now(), span=Math.min(2_000_000_000L,Math.max(0,now-imageStart));
        var recent=imageHistory.stream().filter(i->i.time()>now-2_000_000_000L).toList();
        long hold=imageContext==null?0:Math.max(0,now-lastImageTime);
        var intervals=new ArrayList<Double>();
        for(var i:recent) intervals.add(i.intervalNanos()/1e6);
        if(imageContext!=null && (intervals.isEmpty() || hold>intervals.getLast()*1e6)) intervals.add(hold/1e6);
        double[] values=intervals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] ages=recent.stream().mapToDouble(i->i.ageNanos()/1e6).sorted().toArray();
        return new ImageSnapshot(List.copyOf(imageHistory),span<250_000_000L?-1:recent.size()*1e9/span,
                Arrays.stream(values).average().orElse(0),percentile(values),hold/1e6,
                capturedNanos<0?-1:Math.max(0,now-capturedNanos)/1e6,percentile(ages),imageStatus);
    }
    private static double percentile(double[] sorted) { return sorted.length==0?0:sorted[(int)Math.ceil(sorted.length*.95)-1]; }
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
