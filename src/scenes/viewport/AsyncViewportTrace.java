package scenes.viewport;

import profiling.TraceProfile;
import java.util.concurrent.*;

/** One active coordinator, no queued snapshots. Publication buffers are never written again. */
final class AsyncViewportTrace implements AutoCloseable {
    record Image(float[][][] rgb, ViewportState.RenderKey key, long generation, long requestedNanos,
                 long finishedNanos, long samples, TraceProfile.Stats stats, long traceNanos,
                 int primaryRays, int primaryHits, int shadowRays, int shadowsOccluded, int litPixels) {}
    private final ViewportState live;
    private final DirectRgbTracer tracer;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "viewport-trace-coordinator"); thread.setDaemon(true); return thread;
    });
    private volatile long epoch;
    private volatile boolean closed;
    private volatile Image image;
    private volatile Throwable failure;
    private ViewportState.RenderKey key;
    private boolean running, wasPaused;
    private volatile long generation;
    private long requestedNanos, firstImageNanos=-1, target;
    private volatile long cancelledJobs, wastedRays, maxTileNanos;

    AsyncViewportTrace(ViewportState live, DirectRgbTracer tracer) { this.live=live; this.tracer=tracer; }
    /** Called under live state lock from input as well as presentation. */
    void invalidate() {
        var next=live.renderKey();
        if (!next.equals(key)) {
            // Finishing one complete camera snapshot guarantees progress under sustained movement.
            // Transport/resolution/restart changes still cancel between tiles.
            if (!next.sameTransport(key)) epoch++;
            key=next; generation++; requestedNanos=System.nanoTime(); firstImageNanos=-1;
            live.accumulatedSamples(0);
        }
        if (wasPaused != live.paused()) { wasPaused=live.paused(); epoch++; }
        if (target != live.sampleTarget()) { target=live.sampleTarget(); epoch++; }
    }
    void request() {
        invalidate();
        if (failure != null) throw new IllegalStateException("Background trace failed", failure);
        if (closed || running) return;
        var current=image;
        if (current != null && current.generation()==generation && current.key().equals(key)
                && (live.paused() || current.samples() >= live.effectiveTarget())) return;
        var snapshot=live.renderSnapshot();
        long token=epoch, jobGeneration=generation, requested=System.nanoTime();
        var jobKey=key;
        running=true;
        coordinator.execute(() -> {
            boolean published=false;
            try {
                var rgb=tracer.trace(snapshot, () -> closed || token != epoch,
                        () -> jobGeneration != generation);
                maxTileNanos=Math.max(maxTileNanos,tracer.maxTileNanos);
                if (closed || token != epoch) return;
                var owned=new float[3][][];
                for(int c=0;c<3;c++) {
                    owned[c]=new float[rgb[c].length][];
                    for(int y=0;y<rgb[c].length;y++) owned[c][y]=rgb[c][y].clone();
                }
                synchronized(live) {
                    var liveKey=live.renderKey();
                    if (closed || token != epoch || !jobKey.sameTransport(liveKey)) return;
                    image=new Image(owned,jobKey,jobGeneration,requested,System.nanoTime(),snapshot.accumulatedSamples(),
                            tracer.profile,tracer.traceNanos,tracer.primaryRays,tracer.primaryHits,tracer.shadowRays,
                            tracer.shadowsOccluded,tracer.litPixels);
                    // A lagging camera image is an explicit preview, not samples of the newest camera.
                    if (jobKey.equals(liveKey)) live.accumulatedSamples(snapshot.accumulatedSamples());
                    published=true;
                }
            } catch(Throwable error) { failure=error; }
            finally { synchronized(live) {
                if(!published) {
                    cancelledJobs++;
                    wastedRays+=jobKey.equals(live.renderKey()) ? tracer.discardedPrimaryRays : tracer.primaryRays;
                }
                running=false; if(closed) tracer.close();
            } }
        });
    }
    Image image() { return image; }
    void presented(Image shown) {
        if(shown != null && shown.generation()==generation && shown.samples()>0 && firstImageNanos<0)
            firstImageNanos=System.nanoTime()-requestedNanos;
    }
    profiling.FrameProfiler.RenderProgress progress(Image shown) {
        return new profiling.FrameProfiler.RenderProgress(generation,shown==null ? -1 : shown.generation(),
                shown==null ? 0 : shown.samples(),shown==null ? -1 : System.nanoTime()-shown.requestedNanos(),
                firstImageNanos,cancelledJobs,wastedRays,maxTileNanos,running);
    }
    String status() {
        var current=image;
        return "gen " + generation + " shown " + (current==null ? "none" : current.generation())
                + " | age " + (current==null ? "n/a" : (System.nanoTime()-current.requestedNanos())/1_000_000+"ms")
                + " | cancelled " + cancelledJobs + " waste " + wastedRays + " paths"
                + " | tile max " + maxTileNanos/1_000_000 + "ms"
                + " | first image " + (firstImageNanos<0 ? "pending" : firstImageNanos/1_000_000+"ms");
    }
    void suspend() { epoch++; }
    @Override public void close() {
        synchronized(live) { closed=true; epoch++; if(!running) tracer.close(); }
        coordinator.shutdown();
    }
}
