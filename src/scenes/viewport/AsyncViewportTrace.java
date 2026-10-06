package scenes.viewport;

import profiling.TraceProfile;
import java.util.concurrent.*;

/** One active coordinator, no queued snapshots. Published storage is immutable while leased. */
final class AsyncViewportTrace implements AutoCloseable {
    record Image(float[][][] rgb, ViewportState.RenderKey key, long generation, long requestedNanos,
                 long finishedNanos, long samples, TraceProfile.Stats stats, long traceNanos,
                 int primaryRays, int primaryHits, int shadowRays, int shadowsOccluded, int litPixels,
                 Slot slot, boolean interactiveMotion, boolean temporal,
                 long temporalVersion, float[][][] reconstructed, TemporalReconstruction.Stats history,
                 int requestedBatch, int plannedBatch, boolean motionBudget) {}
    private static final class Slot {
        float[][][] rgb;
        float[][][] reconstructed;
        int readers;
        boolean writing;
        Image owner;
    }
    // Latest publication, leased display image, and coordinator copy. Never grow with motion.
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
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
    private final InteractiveResolution resolution = new InteractiveResolution();
    private long measuredNanos=-1;
    private long temporalVersion;
    private final TemporalReconstruction reconstruction=new TemporalReconstruction();
    private long historyEpoch=-1;

    AsyncViewportTrace(ViewportState live, DirectRgbTracer tracer) { this.live=live; this.tracer=tracer; }
    /** Called under live state lock from input as well as presentation. */
    void invalidate() {
        resolution.observe(live,System.nanoTime());
        if(temporalVersion!=live.temporalVersion()) { temporalVersion=live.temporalVersion();epoch++; }
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
        // Change grids only after the active pass has published. Mouse events never cancel it.
        resolution.choose(live,System.nanoTime());
        invalidate();
        var current=image;
        if (current != null && current.temporalVersion()==live.temporalVersion() && current.generation()==generation && current.key().equals(key)
                && (live.paused() || current.samples() >= live.effectiveTarget())) return;
        Slot destination=null;
        for(var slot:slots) if(!slot.writing && slot.readers==0 && (current==null || slot!=current.slot())) {
            destination=slot; break;
        }
        if(destination==null) return; // A reader must release a lease before more work is useful.
        final Slot output=destination;
        output.writing=true;
        output.owner=null;
        var snapshot=live.renderSnapshot();
        boolean moving=resolution.moving(System.nanoTime());
        boolean budget=moving && live.temporalBudgetSupported();
        boolean motion=moving && (live.interactive() || budget);
        int requestedBatch=live.samplesPerFrame();
        snapshot.samplesPerFrame(live.movingBatch(moving));
        int plannedBatch=snapshot.samplesPerFrame();
        boolean p4Motion=moving && live.interactive();
        long token=epoch, jobGeneration=generation, requested=System.nanoTime();
        var jobKey=key;
        running=true;
        coordinator.execute(() -> {
            boolean published=false;
            try {
                if(historyEpoch!=token) { reconstruction.clear();historyEpoch=token; }
                // Without compatible history, spend the requested samples to seed a useful image.
                int chosenBatch=budget && !p4Motion && !reconstruction.compatibleHistory(jobKey,System.nanoTime())
                        ?requestedBatch:plannedBatch;
                snapshot.samplesPerFrame(chosenBatch);
                var rgb=tracer.trace(snapshot, () -> closed || token != epoch,
                        () -> jobGeneration != generation);
                maxTileNanos=Math.max(maxTileNanos,tracer.maxTileNanos);
                if (closed || token != epoch) return;
                int h=rgb[0].length,w=rgb[0][0].length;
                if(output.rgb==null || output.rgb[0].length!=h || output.rgb[0][0].length!=w)
                    output.rgb=new float[3][h][w];
                var owned=output.rgb;
                for(int c=0;c<3;c++) for(int y=0;y<h;y++) System.arraycopy(rgb[c][y],0,owned[c][y],0,w);
                float[][][] presentation=null;
                if(snapshot.temporal()) {
                    var result=reconstruction.reconstruct(rgb,tracer.surfaceGuide(),jobKey,snapshot.accumulatedSamples(),System.nanoTime(),snapshot.workers(),
                            ()->closed || token!=epoch);
                    if(result!=rgb) {
                        if(output.reconstructed==null || output.reconstructed[0].length!=h || output.reconstructed[0][0].length!=w)
                            output.reconstructed=new float[3][h][w];
                        presentation=output.reconstructed;
                        for(int c=0;c<3;c++) for(int y=0;y<h;y++) System.arraycopy(result[c][y],0,presentation[c][y],0,w);
                    }
                } else { reconstruction.clear(); for(var slot:slots) slot.reconstructed=null; }
                synchronized(live) {
                    var liveKey=live.renderKey();
                    if (closed || token != epoch || !jobKey.sameTransport(liveKey)) return;
                    image=new Image(owned,jobKey,jobGeneration,requested,System.nanoTime(),snapshot.accumulatedSamples(),
                            tracer.profile,tracer.traceNanos,tracer.primaryRays,tracer.primaryHits,tracer.shadowRays,
                            tracer.shadowsOccluded,tracer.litPixels,output,motion,snapshot.temporal(),snapshot.temporalVersion(),presentation,reconstruction.stats(),
                            requestedBatch,chosenBatch,budget);
                    output.owner=image;
                    output.writing=false; // Readers may acquire as soon as publication releases this lock.
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
                output.writing=false;
                running=false; if(closed) tracer.close();
            } }
        });
    }
    /** Metadata only unless retained under the live monitor; RGB must not escape its lease.
     * acquireImage/retain/release must all be called under that monitor. */
    Image image() { return image; }
    Image acquireImage() { return retain(image); }
    Image retain(Image shown) {
        if(shown!=null) {
            if(shown.slot().owner!=shown || shown.slot().writing)
                throw new IllegalStateException("Publication has already been recycled");
            shown.slot().readers++;
        }
        return shown;
    }
    void release(Image shown) {
        if(shown!=null) {
            if(shown.slot().owner!=shown || shown.slot().readers==0)
                throw new IllegalStateException("Unbalanced image lease");
            shown.slot().readers--;
        }
    }
    void presented(Image shown) {
        if(shown != null && shown.requestedNanos() != measuredNanos && shown.samples()>0) {
            measuredNanos=shown.requestedNanos();
            if(shown.interactiveMotion()) resolution.completed(shown.key().width(),shown.key().height(),
                    System.nanoTime()-shown.requestedNanos());
        }
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
        return "sampled " + (current==null ? "pending" : current.key().width()+"x"+current.key().height())
                + " / requested " + live.sensorPixelsW()+"x"+live.sensorPixelsH()
                + (live.interactive() ? " auto" : " fixed") + " | gen " + generation + " shown " + (current==null ? "none" : current.generation())
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
