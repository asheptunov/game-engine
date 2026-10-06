package engine;

import java.util.Objects;

/** Package-private coordinator adapter preserving the established tracer and three-slot publication pool. */
final class DefaultRenderSession implements RenderSession {
    private final ViewportState state;
    private final AsyncViewportTrace async;
    private WorldSnapshot world;
    private RenderView view;
    private RenderSettings settings;
    private long appliedRestart;
    private boolean closed;

    DefaultRenderSession(WorldSnapshot world, RenderView view, RenderSettings settings) {
        Objects.requireNonNull(world); Objects.requireNonNull(view); Objects.requireNonNull(settings);
        state = new ViewportState(view.camera().sensor(), view.width(), view.height());
        async = new AsyncViewportTrace(state, new DirectRgbTracer(state));
        update(world, view, settings);
    }

    DefaultRenderSession(ViewportState state, DirectRgbTracer tracer) {
        this.state=Objects.requireNonNull(state);
        this.async=new AsyncViewportTrace(state, Objects.requireNonNull(tracer));
    }

    @Override public void update(WorldSnapshot nextWorld, RenderView nextView, RenderSettings nextSettings) {
        Objects.requireNonNull(nextWorld); Objects.requireNonNull(nextView); Objects.requireNonNull(nextSettings);
        // Constructors validate most invariants; repeat camera validation before touching the live adapter.
        nextView.camera().validated();
        synchronized(state) {
            checkOpen();
            if (!nextWorld.equals(world)) {
                state.instances().clear(); state.instances().addAll(nextWorld.instances());
                state.objects().clear(); state.objects().addAll(nextWorld.legacyObjects());
                state.lights().clear(); state.lights().addAll(nextWorld.lights());
                if (world != null || nextWorld.revision() != 0) state.restart();
                world=nextWorld;
            }
            if (!nextView.equals(view)) {
                state.camera(nextView.camera());
                if (state.sensorPixelsW()!=nextView.width() || state.sensorPixelsH()!=nextView.height())
                    state.resolution(nextView.width(),nextView.height());
                view=nextView;
            }
            if (!nextSettings.equals(settings)) {
                apply(nextSettings);
                settings=nextSettings;
            }
            async.invalidate();
        }
    }

    private void apply(RenderSettings value) {
        state.pathDepth(value.pathDepth());
        state.seed(value.seed());
        if (settings != null && value.restartRevision()!=appliedRestart) state.restart();
        appliedRestart=value.restartRevision();
        state.samplesPerFrame(value.samplesPerBatch());
        state.sampleTarget(value.sampleTarget());
        state.paused(value.paused());
        state.acceleration(value.acceleration());
        state.workers(value.workers());
        state.tileSize(value.tileSize());
        state.temporal(value.temporal(),value.temporalRevision());
        state.interactive(value.interactive());
        state.interactiveMillis(value.interactiveMillis());
        if(value.minimumWidth()==0)state.interactiveMinimumDefault();
        else state.interactiveMinimum(value.minimumWidth(),value.minimumHeight());
        state.temporalBudget(value.temporalBudget());
        state.motionSamples(value.motionSamples());
        state.motionScale(value.motionScale());
    }

    @Override public void invalidate() { synchronized(state) { checkOpen(); async.invalidate(); } }
    @Override public void request() { synchronized(state) { checkOpen(); async.request(); } }

    @Override public RenderImage acquireImage() {
        synchronized(state) {
            checkOpen();
            var image=async.acquireImage();
            return image==null?null:new Lease(this,image);
        }
    }

    @Override public void presented(RenderImage image) {
        synchronized(state) { checkOpen(); async.presented(unwrapNullable(image)); }
    }

    @Override public RenderProgress progress(RenderImage shown) {
        synchronized(state) {
            var p=async.progress(unwrapNullable(shown));
            return p;
        }
    }

    @Override public String status() { synchronized(state) { return async.status(); } }
    @Override public void suspend() { synchronized(state) { checkOpen(); async.suspend(); } }
    @Override public boolean closed() { synchronized(state) { return closed; } }
    @Override public void close() { synchronized(state) { if(!closed){closed=true;async.close();} } }

    private AsyncViewportTrace.Image unwrap(RenderImage image) {
        var lease=(Lease)Objects.requireNonNull(image);
        if(lease.owner!=this || lease.closed)throw new IllegalArgumentException("Image lease is closed or belongs to another session");
        return lease.image;
    }
    private AsyncViewportTrace.Image unwrapNullable(RenderImage image) {return image==null?null:unwrap(image);}
    private void release(Lease lease) {
        synchronized(state) {
            if(lease.closed)return;
            async.release(lease.image);lease.closed=true;
        }
    }
    private void checkOpen() {if(closed)throw new IllegalStateException("Render session is closed");}
    ViewportState stateForTests(){return state;}

    private static final class Lease implements RenderImage {
        private final DefaultRenderSession owner;
        private final AsyncViewportTrace.Image image;
        private RgbPixels raw;
        private RgbPixels presentation;
        private boolean closed;
        Lease(DefaultRenderSession owner,AsyncViewportTrace.Image image){this.owner=owner;this.image=image;}
        private void open(){if(closed)throw new IllegalStateException("Image lease is closed");}
        @Override public int width(){open();return image.key().width();}
        @Override public int height(){open();return image.key().height();}
        @Override public long samples(){open();return image.samples();}
        @Override public long generation(){open();return image.generation();}
        @Override public long publicationNanos(){open();return image.requestedNanos();}
        @Override public long finishedNanos(){open();return image.finishedNanos();}
        @Override public Camera camera(){open();return image.camera();}
        @Override public RgbPixels rawPixels(){open();return raw==null?raw=new Pixels(false):raw;}
        @Override public RgbPixels presentationPixels(){open();return presentation==null?presentation=new Pixels(true):presentation;}
        @Override public boolean reconstructed(){open();return image.reconstructed()!=null;}
        @Override public long temporalRevision(){open();return image.temporalVersion();}
        @Override public long cameraHistoryRevision(){open();return image.cameraHistoryVersion();}
        @Override public profiling.TraceProfile.Stats traceStats(){open();return image.stats();}
        @Override public long traceNanos(){open();return image.traceNanos();}
        @Override public int primaryRays(){open();return image.primaryRays();}
        @Override public int primaryHits(){open();return image.primaryHits();}
        @Override public int shadowRays(){open();return image.shadowRays();}
        @Override public int shadowsOccluded(){open();return image.shadowsOccluded();}
        @Override public int litPixels(){open();return image.litPixels();}
        @Override public int requestedBatch(){open();return image.requestedBatch();}
        @Override public int plannedBatch(){open();return image.plannedBatch();}
        @Override public boolean motionBudget(){open();return image.motionBudget();}
        @Override public String historyLabel(){open();return image.history().label();}
        @Override public boolean closed(){return closed;}
        @Override public void close(){owner.release(this);}
        private final class Pixels implements RgbPixels {
            private final boolean reconstructed;
            Pixels(boolean reconstructed){this.reconstructed=reconstructed;}
            private float[][][] data(){open();return reconstructed && image.reconstructed()!=null?image.reconstructed():image.rgb();}
            @Override public int width(){return Lease.this.width();}
            @Override public int height(){return Lease.this.height();}
            @Override public float value(int channel,int x,int y) {
                if(channel<0||channel>2||x<0||x>=width()||y<0||y>=height())throw new IndexOutOfBoundsException();
                return data()[channel][y][x];
            }
            @Override public void copyTo(float[][][] destination) {
                var source=data();int h=source[0].length,w=source[0][0].length;
                if(destination.length!=3)throw new IllegalArgumentException("RGB destination needs three channels");
                for(int c=0;c<3;c++) {
                    if(destination[c].length!=h)throw new IllegalArgumentException("RGB destination height mismatch");
                    for(int y=0;y<h;y++) {
                        if(destination[c][y].length!=w)throw new IllegalArgumentException("RGB destination width mismatch");
                        System.arraycopy(source[c][y],0,destination[c][y],0,w);
                    }
                }
            }
        }
    }
}
