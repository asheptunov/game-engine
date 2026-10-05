package scenes.viewport;

import math.Ray;
import math.Vec3;
import profiling.RuntimeMetrics;
import profiling.TraceProfile;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.SceneObject;
import java.util.List;

/** Iterative RGB paths, GGX/delta transport and area-light MIS; no per-ray objects. */
public final class DirectRgbTracer implements AutoCloseable {
    private static final float BIAS = 1e-3f;
    private ViewportState state;
    private List<SceneInstance> cachedInstances = List.of();
    private List<SceneObject> cachedLegacyObjects = List.of();
    private PreparedObject[] objects = new PreparedObject[0];
    private PreparedEmitter[] emitters = new PreparedEmitter[0];
    private float[][][] buffer;
    private double[][][] mean;
    private double[][][] stagingMean;
    private java.util.function.BooleanSupplier cancellation = () -> false;
    private volatile long sliceDeadline;
    private final java.util.concurrent.atomic.AtomicInteger completedTiles = new java.util.concurrent.atomic.AtomicInteger();
    public boolean cancelled;
    public long maxTileNanos, discardedPrimaryRays;
    private AccumulationKey accumulationKey;
    private long samples;
    private boolean volumeMode;
    private int visibilityCrossingLimit;
    public long volumeSegments, volumeEvents, volumeVisibilitySegments;
    /** Snapshot mutable lists; display and sample-batch settings do not change the estimator. */
    private record AccumulationKey(List<SceneInstance> instances, List<SceneObject> legacy,
                                   List<scenes.viewport.lights.Light> lights, Vec3 eye,
                                   scenes.viewport.objects.Rect sensor, int width, int height,
                                   int depth, long seed, long restart) {}

    /** Pixel/sample-local stream, independent of batch boundaries and traversal lengths. */
    static final class Sampler {
        private long value;
        void reset(long seed, long pixel, long sample) {
            value = mix(seed) ^ mix(pixel + 0x632be59bd9b4e019L) ^ mix(sample + 0x8cb92baa3f3d8dd7L);
        }
        private static long mix(long n) {
            n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
            n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
            return n ^ (n >>> 31);
        }
        float next() { value += 0x9e3779b97f4a7c15L; return (mix(value) >>> 40) * 0x1.0p-24f; }
    }

    public int primaryRays, primaryHits, shadowRays, shadowsOccluded, litPixels;
    public long traceNanos, primaryTests, continuationTests, shadowTests, continuationRays;
    public long dielectricReflections, dielectricTransmissions, absorptionSegments;
    public long areaLightSamples, emitterHits, roughEvents;
    public long geometryBuilds;
    public TraceProfile.Stats profile;

    /** Retain the geometric normal and orientation; derive the shading normal separately. */
    static final class Hit {
        PreparedPrimitive primitive;
        PreparedObject object;
        float x, y, z, nx, ny, nz, distance;
        boolean frontFace;
    }

    public DirectRgbTracer(ViewportState state) {
        this.state = state;
        buffer = new float[3][state.sensorPixelsH()][state.sensorPixelsW()];
    }

    private void prepare() {
        var instances = List.copyOf(state.instances());
        var legacy = List.copyOf(state.objects());
        if (instances.equals(cachedInstances) && legacy.equals(cachedLegacyObjects)) return;
        geometryBuilds++;
        var prepared = new java.util.ArrayList<PreparedObject>();
        var emitting = new java.util.ArrayList<PreparedEmitter>();
        for (var instance : instances) {
            var object=new PreparedObject(instance);prepared.add(object);
            if(instance.material().emissive()) emitting.add(new PreparedEmitter(object,instance));
        }
        for (int i = 0; i < legacy.size(); i++) {
            var instance = new SceneInstance("primitive-" + i, List.of(legacy.get(i)), Transform.IDENTITY,
                    new Material("white", new Vec3(1, 1, 1)));
            prepared.add(new PreparedObject(instance));
        }
        objects = prepared.toArray(PreparedObject[]::new);
        volumeMode=instances.stream().anyMatch(o->o.material().scattering()>0);
        visibilityCrossingLimit=2+prepared.stream().mapToInt(o->o.primitives.length+1).sum();
        emitters = emitting.toArray(PreparedEmitter[]::new);
        cachedInstances = instances;
        cachedLegacyObjects = legacy;
    }

    // One lazily started process-wide pool: scene switches and test tracers do not multiply threads.
    private static final class Pool {
        private static final int LIMIT = Math.min(32, Runtime.getRuntime().availableProcessors());
        static final java.util.concurrent.ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
                LIMIT, LIMIT, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(128), runnable -> {
                    var thread = new Thread(runnable, "rgb-trace-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        static { Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            EXECUTOR.shutdown();
            try { if (!EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) EXECUTOR.shutdownNow(); }
            catch (InterruptedException e) { EXECUTOR.shutdownNow(); Thread.currentThread().interrupt(); }
        }, "rgb-trace-shutdown")); }
    }
    private record Snapshot(PreparedObject[] objects, PreparedEmitter[] emitters, PointLight[] lights,
                            Vec3 eye, scenes.viewport.objects.Rect sensor, int width, int height,
                            int depth, long seed, long sample, boolean acceleration,
                            boolean volumeMode, int crossingLimit) {}
    private final java.util.concurrent.atomic.AtomicInteger nextTile = new java.util.concurrent.atomic.AtomicInteger();
    private List<Worker> workers = List.of();
    private boolean closed;
    private Worker testWorker;
    public long workerCpuNanos, workerAllocatedBytes;
    /** Completed linear radiance, reused on the next trace; callers must not mutate it. */
    public float[][][] radianceBuffer() { return buffer; }

    /** Blocking reference path: caller owns the supplied state and prevents concurrent edits. */
    public float[][][] trace() {
        return trace(state, () -> false);
    }
    /** Single coordinator only; snapshot is owned by the caller, never the live input state. */
    public float[][][] trace(ViewportState snapshotState, java.util.function.BooleanSupplier cancel) {
        return trace(snapshotState,cancel,()->false);
    }
    /** Camera motion may finish the current pass but suppress further passes in this batch. */
    public float[][][] trace(ViewportState snapshotState, java.util.function.BooleanSupplier cancel,
                             java.util.function.BooleanSupplier stopAfterPass) {
        state = snapshotState;
        cancellation = cancel;
        cancelled = false; maxTileNanos = discardedPrimaryRays = 0;
        if (closed) throw new IllegalStateException("Tracer is closed");
        long cpu = RuntimeMetrics.threadCpu(), bytes = RuntimeMetrics.allocatedBytes(), start = System.nanoTime();
        prepare();
        primaryRays = 0;
        primaryHits = 0;
        shadowRays = 0;
        shadowsOccluded = 0;
        litPixels = 0;
        primaryTests = 0;
        continuationTests = 0;
        shadowTests = 0;
        continuationRays = 0;
        dielectricReflections = 0;
        dielectricTransmissions = 0;
        absorptionSegments = 0;
        areaLightSamples = 0;
        emitterHits = 0;
        roughEvents = 0;
        volumeSegments = 0;
        volumeEvents = 0;
        volumeVisibilitySegments = 0;

        workerCpuNanos = workerAllocatedBytes = 0;
        var eye = state.eye(); var sensor = state.cameraSensor();
        int width = state.sensorPixelsW(), height = state.sensorPixelsH();
        var key = new AccumulationKey(cachedInstances, cachedLegacyObjects, List.copyOf(state.lights()),
                eye, sensor, width, height, state.pathDepth(), state.seed(), state.restartVersion());
        if (!key.equals(accumulationKey)) {
            accumulationKey = key; samples = 0; state.accumulatedSamples(0);
            if (mean == null || buffer[0].length != height || buffer[0][0].length != width) {
                mean = new double[3][height][width]; stagingMean = new double[3][height][width];
                buffer = new float[3][height][width];
            } else for (int c = 0; c < 3; c++) for (int y = 0; y < height; y++) {
                java.util.Arrays.fill(mean[c][y], 0); java.util.Arrays.fill(buffer[c][y], 0);
            }
        }
        int batch = state.paused() ? 0 : (int)Math.min(state.samplesPerFrame(), Math.max(0, state.effectiveTarget()-samples));
        int count = state.workers();
        if (workers.size() != count) {
            var list = new java.util.ArrayList<Worker>();
            for (int i=0; i<count; i++) list.add(new Worker());
            workers = List.copyOf(list);
        }
        var lights = state.lights().stream().filter(PointLight.class::isInstance).map(PointLight.class::cast).toArray(PointLight[]::new);
        int rendered = 0;
        for (int pass=0; pass<batch; pass++) {
            var snapshot = new Snapshot(objects, emitters, lights, eye, sensor, width, height,
                    state.pathDepth(), state.seed(), samples, state.acceleration(), volumeMode, visibilityCrossingLimit);
            nextTile.set(0);
            completedTiles.set(0);
            for (var worker : workers) worker.configure(snapshot, state.tileSize());
            do {
                sliceDeadline = System.nanoTime() + 16_666_667L;
                // All workers finish before commit, including on failure/interruption. Never expose mixed passes.
                if (count == 1) {
                    try { workers.getFirst().call(); }
                    catch (RuntimeException | Error failure) { accumulationKey = null; throw failure; }
                }
                else {
                    var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
                    Throwable failure = null; boolean interrupted = false;
                    try {
                        for (var worker : workers) futures.add(Pool.EXECUTOR.submit(worker));
                    } catch (RuntimeException e) { failure = e; }
                    for (var future : futures) {
                        boolean done = false;
                        while (!done) try { future.get(); done = true; }
                        catch (InterruptedException e) { interrupted = true; }
                        catch (java.util.concurrent.ExecutionException e) { failure = e.getCause(); done = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    if (failure != null || interrupted) {
                        accumulationKey = null; // Invalidate any writes from this uncommitted pass.
                        throw new IllegalStateException("Trace pass failed", failure);
                    }
                }
            } while (!cancellation.getAsBoolean() && nextTile.get() < workers.getFirst().tileCount);
            for (var worker : workers) {
                maxTileNanos = Math.max(maxTileNanos, worker.maxTileNanos);
                primaryRays += worker.primaryRays;
                primaryHits += worker.primaryHits;
                shadowRays += worker.shadowRays;
                shadowsOccluded += worker.shadowsOccluded;
                litPixels += worker.litPixels;
                primaryTests += worker.primaryTests;
                continuationTests += worker.continuationTests;
                shadowTests += worker.shadowTests;
                continuationRays += worker.continuationRays;
                dielectricReflections += worker.dielectricReflections;
                dielectricTransmissions += worker.dielectricTransmissions;
                absorptionSegments += worker.absorptionSegments;
                areaLightSamples += worker.areaLightSamples;
                emitterHits += worker.emitterHits;
                roughEvents += worker.roughEvents;
                volumeSegments += worker.volumeSegments;
                volumeEvents += worker.volumeEvents;
                volumeVisibilitySegments += worker.volumeVisibilitySegments;

                // Inline one-worker execution is already included in the coordinator counters.
                if (count > 1) {
                    workerCpuNanos = addMetric(workerCpuNanos, worker.cpuNanos);
                    workerAllocatedBytes = addMetric(workerAllocatedBytes, worker.allocatedBytes);
                }
            }
            if (cancellation.getAsBoolean() || completedTiles.get() != workers.getFirst().tileCount) {
                cancelled = true;
                discardedPrimaryRays = workers.stream().mapToLong(w -> w.primaryRays).sum();
                break;
            }
            var previous = mean; mean = stagingMean; stagingMean = previous;
            for (int c=0;c<3;c++) for(int y=0;y<height;y++) for(int x=0;x<width;x++)
                buffer[c][y][x]=(float)mean[c][y][x];
            samples++; rendered++;
            if (stopAfterPass.getAsBoolean() || System.nanoTime()-start >= 50_000_000L) break;
        }
        state.accumulatedSamples(samples);
        traceNanos = System.nanoTime()-start;
        profile = new TraceProfile.Stats(addMetric(RuntimeMetrics.delta(cpu, RuntimeMetrics.threadCpu()), workerCpuNanos),
                addMetric(RuntimeMetrics.delta(bytes, RuntimeMetrics.allocatedBytes()), workerAllocatedBytes), primaryTests, shadowTests,
                state.preset(), state.pathDepth(), rendered, continuationRays, continuationTests,
                samples, state.samplingStatus(), state.seed(), dielectricReflections, dielectricTransmissions, absorptionSegments,
                areaLightSamples, emitterHits, roughEvents, volumeSegments, volumeEvents, volumeVisibilitySegments,
                count, state.tileSize());
        return buffer;
    }
    private static long addMetric(long a, long b) { return a < 0 || b < 0 ? -1 : a+b; }
    @Override public void close() { closed = true; workers = List.of(); }

    private Worker adapter() {
        prepare(); var worker = new Worker();
        worker.configure(new Snapshot(objects, emitters, new PointLight[0], state.eye(), state.cameraSensor(),
                state.sensorPixelsW(), state.sensorPixelsH(), state.pathDepth(), state.seed(), 0,
                state.acceleration(), volumeMode, visibilityCrossingLimit), state.tileSize());
        return worker;
    }
    float[] radiance(Ray ray, long sample) {
        var worker = adapter(); var origin = ray.origin(); var direction = ray.direction().normalized();
        worker.sampler.reset(state.seed(), 0, sample);
        var lights = state.lights().stream().filter(PointLight.class::isInstance).map(PointLight.class::cast).toArray(PointLight[]::new);
        worker.path(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), worker.hit,
                lights, worker.rgb, worker.lighting, worker.sampler, worker.scattering, worker.evaluation,
                worker.mediaAt(origin.x(), origin.y(), origin.z()), worker.media);
        collectAdapter(worker);
        return worker.rgb;
    }
    Hit intersect(Ray ray) {
        var worker = adapter(); var origin = ray.origin(); var direction = ray.direction();
        boolean found = worker.nearestHit(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), worker.hit);
        collectAdapter(worker);
        return found ? worker.hit : null;
    }
    boolean nearestHit(float ox,float oy,float oz,float dx,float dy,float dz,Hit hit) {
        var worker = adapter(); boolean found = worker.nearestHit(ox,oy,oz,dx,dy,dz,hit);
        collectAdapter(worker); return found;
    }
    boolean occluded(float ox,float oy,float oz,float dx,float dy,float dz,float limit,PreparedPrimitive source) {
        var worker = adapter(); boolean found = worker.occluded(ox,oy,oz,dx,dy,dz,limit,source);
        collectAdapter(worker); return found;
    }
    boolean volumeVisibility(float ox,float oy,float oz,float dx,float dy,float dz,float limit,PreparedObject target) {
        testWorker = adapter(); boolean visible = testWorker.volumeVisibility(ox,oy,oz,dx,dy,dz,limit,target);
        collectAdapter(testWorker); return visible;
    }
    float[] visibilityTransmission() { return testWorker.transmission.clone(); }
    private void collectAdapter(Worker worker) {
        primaryHits += worker.primaryHits; primaryTests += worker.primaryTests;
        shadowTests += worker.shadowTests; continuationTests += worker.continuationTests;
        continuationRays += worker.continuationRays;
        dielectricReflections += worker.dielectricReflections; dielectricTransmissions += worker.dielectricTransmissions;
        absorptionSegments += worker.absorptionSegments;
        areaLightSamples += worker.areaLightSamples; emitterHits += worker.emitterHits; roughEvents += worker.roughEvents;
        volumeSegments += worker.volumeSegments; volumeEvents += worker.volumeEvents;
        volumeVisibilitySegments += worker.volumeVisibilitySegments;
    }
    /** Power heuristic for one sample of each strategy, calculated in double to avoid overflow. */
    static float mis(float a,float b) {
        double aa=(double)a*a,bb=(double)b*b;return aa+bb>0?(float)(aa/(aa+bb)):0;
    }
    private final class Worker implements java.util.concurrent.Callable<Void> {
        private Snapshot snapshot;
        private PreparedObject[] objects, media, initialMedia;
        private PreparedEmitter[] emitters;
        private int tileSize, tilesX, tileCount;
        private final Hit hit = new Hit();
        private final float[] rgb = new float[3], lighting = new float[3];
        private final Sampler sampler = new Sampler();
        private final Material.Sample scattering = new Material.Sample(), evaluation = new Material.Sample();
        private long cpuNanos, allocatedBytes;
        private long maxTileNanos;
        private boolean continuation;
        private final Hit visibilityHit = new Hit();
        private PreparedObject[] visibilityMedia = new PreparedObject[0];
        private float[] visibilityDistances = new float[0];
        private final float[] transmission = new float[3];
        private boolean volumeMode;
        private int visibilityCrossingLimit;

        private int primaryRays;
        private int primaryHits;
        private int shadowRays;
        private int shadowsOccluded;
        private int litPixels;
        private long primaryTests;
        private long continuationTests;
        private long shadowTests;
        private long continuationRays;
        private long dielectricReflections;
        private long dielectricTransmissions;
        private long absorptionSegments;
        private long areaLightSamples;
        private long emitterHits;
        private long roughEvents;
        private long volumeSegments;
        private long volumeEvents;
        private long volumeVisibilitySegments;

        void configure(Snapshot value, int tile) {
            cpuNanos = allocatedBytes = maxTileNanos = 0;
            snapshot=value; objects=value.objects(); emitters=value.emitters();
            volumeMode=value.volumeMode(); visibilityCrossingLimit=value.crossingLimit();
            if (media == null || media.length != objects.length+1) {
                media=new PreparedObject[objects.length+1];
                visibilityMedia=new PreparedObject[objects.length+1];
                visibilityDistances=new float[objects.length+1];
            }
            initialMedia=mediaAt(value.eye().x(), value.eye().y(), value.eye().z());
            tileSize=tile; tilesX=(value.width()+tile-1)/tile;
            tileCount=tilesX*((value.height()+tile-1)/tile);
            primaryRays = 0;
            primaryHits = 0;
            shadowRays = 0;
            shadowsOccluded = 0;
            litPixels = 0;
            primaryTests = 0;
            continuationTests = 0;
            shadowTests = 0;
            continuationRays = 0;
            dielectricReflections = 0;
            dielectricTransmissions = 0;
            absorptionSegments = 0;
            areaLightSamples = 0;
            emitterHits = 0;
            roughEvents = 0;
            volumeSegments = 0;
            volumeEvents = 0;
            volumeVisibilitySegments = 0;

        }
        @Override public Void call() {
            long cpu=RuntimeMetrics.threadCpu(), bytes=RuntimeMetrics.allocatedBytes();
            try {
                int tile;
                while (!cancellation.getAsBoolean() && System.nanoTime() < sliceDeadline
                        && (tile=nextTile.getAndIncrement()) < tileCount) {
                    long start = System.nanoTime();
                    int x0=tile%tilesX*tileSize, y0=tile/tilesX*tileSize;
                    render(x0,y0,Math.min(snapshot.width(),x0+tileSize),Math.min(snapshot.height(),y0+tileSize));
                    completedTiles.incrementAndGet();
                    maxTileNanos=Math.max(maxTileNanos,System.nanoTime()-start);
                }
                return null;
            } finally {
                cpuNanos=addMetric(cpuNanos,RuntimeMetrics.delta(cpu,RuntimeMetrics.threadCpu()));
                allocatedBytes=addMetric(allocatedBytes,RuntimeMetrics.delta(bytes,RuntimeMetrics.allocatedBytes()));
            }
        }
        private void render(int x0,int y0,int x1,int y1) {
            int width=snapshot.width(),height=snapshot.height();
            var eye=snapshot.eye(); var sensor=snapshot.sensor(); var lights=snapshot.lights();
            double inverseCount=1.0/(snapshot.sample()+1);
            for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
                    if(snapshot.depth()>0 || emitters.length>0) sampler.reset(snapshot.seed(), (long)y*width + x, snapshot.sample());
                    // Preserve the phase 1 pixel-center diagnostic exactly at depth zero.
                    float u = (x + (snapshot.depth() == 0 ? .5f : sampler.next())) / width;
                    float v = (y + (snapshot.depth() == 0 ? .5f : sampler.next())) / height;
                    float dx = sensor.origin().x() + sensor.edge1().x()*u + sensor.edge2().x()*v - eye.x();
                    float dy = sensor.origin().y() + sensor.edge1().y()*u + sensor.edge2().y()*v - eye.y();
                    float dz = sensor.origin().z() + sensor.edge1().z()*u + sensor.edge2().z()*v - eye.z();
                    float inverseLength = 1 / (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    dx *= inverseLength; dy *= inverseLength; dz *= inverseLength;
                    primaryRays++;
                    path(eye.x(), eye.y(), eye.z(), dx, dy, dz, hit, lights, rgb, lighting, sampler, scattering, evaluation, initialMedia, media);
                    for (int c = 0; c < 3; c++) {
                        stagingMean[c][y][x] = mean[c][y][x] + (rgb[c] - mean[c][y][x]) * inverseCount;
                    }
                    if (rgb[0] + rgb[1] + rgb[2] > 0) litPixels++;
            }
        }
        private void path(float ox, float oy, float oz, float dx, float dy, float dz, Hit hit,
                          PointLight[] lights, float[] rgb, float[] lighting, Sampler sampler, Material.Sample scattering, Material.Sample evaluation,
                          PreparedObject[] initialMedia, PreparedObject[] media) {
            rgb[0] = rgb[1] = rgb[2] = 0;
            float red = 1, green = 1, blue = 1;
            float previousPdf=0,previousX=0,previousY=0,previousZ=0;
            boolean previousDelta=true;
            boolean volumeNee=false;
            int mediumCount = initialMedia.length;
            System.arraycopy(initialMedia, 0, media, 0, mediumCount);
            for (int depth = 0; depth <= snapshot.depth(); depth++) {
                continuation = depth != 0;
                if (!nearestHit(ox, oy, oz, dx, dy, dz, hit)) break; // Black environment.
                if (depth == 0) primaryHits++;
                Material medium = mediumCount == 0 ? null : media[mediumCount-1].primitives[0].material;
                float travel=hit.distance;
                boolean volumeEvent=false;
                if(medium!=null && medium.scattering()>0) {
                    volumeSegments++;
                    float freeFlight=Volume.distance(medium.scattering(),sampler.next());
                    volumeEvent=freeFlight<travel;
                    if(volumeEvent)travel=freeFlight;
                    // The sampled survival/collision probabilities cancel sigma_s transmittance.
                    // RGB absorption remains a deterministic weight; do not apply sigma_s twice.
                }
                if (medium != null) {
                    absorptionSegments++;
                    red *= (float)Math.exp(-medium.absorption().x()*travel);
                    green *= (float)Math.exp(-medium.absorption().y()*travel);
                    blue *= (float)Math.exp(-medium.absorption().z()*travel);
                }
                if(volumeEvent) {
                    volumeEvents++;
                    float x=ox+dx*travel,y=oy+dy*travel,z=oz+dz*travel;
                    volumeLight(x,y,z,dx,dy,dz,medium,lights,lighting,sampler);
                    rgb[0]+=red*lighting[0];rgb[1]+=green*lighting[1];rgb[2]+=blue*lighting[2];
                    if(depth==snapshot.depth() || red+green+blue==0)break;
                    Volume.sample(dx,dy,dz,medium.anisotropy(),sampler.next(),sampler.next(),scattering);
                    volumeNee=true;
                    previousDelta=false;previousPdf=scattering.probability;
                    previousX=x;previousY=y;previousZ=z;
                    dx=scattering.dx;dy=scattering.dy;dz=scattering.dz;
                    ox=x;oy=y;oz=z;continuationRays++;continue;
                }
                var material = hit.primitive.material;
                if(material.emissive() && hit.frontFace) {
                    emitterHits++;
                    float weight=1;
                    if(volumeNee)weight=0; // Scattering scenes use NEE-only non-delta emitter connections.
                    if(!volumeNee && depth>0 && !previousDelta) for(var emitter:emitters) if(emitter.object==hit.object) {
                        weight=mis(previousPdf,emitter.pdf(previousX,previousY,previousZ,hit.x,hit.y,hit.z));break;
                    }
                    rgb[0]+=red*material.emission().x()*weight;rgb[1]+=green*material.emission().y()*weight;rgb[2]+=blue*material.emission().z()*weight;
                }
                float incident = medium == null ? 1 : medium.ior();
                float exit = hit.frontFace ? material.ior() : mediumCount < 2 ? 1 : media[mediumCount-2].primitives[0].material.ior();
                if (material.kind() == Material.Kind.DIFFUSE) {
                    light(hit, lights, lighting, medium);
                    rgb[0] += red*lighting[0]; rgb[1] += green*lighting[1]; rgb[2] += blue*lighting[2];
                } else if(!material.delta(incident,exit)) {
                    roughPointLight(hit,lights,lighting,medium,media,mediumCount,dx,dy,dz,incident,exit,evaluation);
                    rgb[0]+=red*lighting[0];rgb[1]+=green*lighting[1];rgb[2]+=blue*lighting[2];
                }
                if(!material.delta(incident,exit) && emitters.length>0) {
                    areaLight(hit,lighting,medium,media,mediumCount,dx,dy,dz,incident,exit,sampler,evaluation,depth<snapshot.depth());
                    rgb[0]+=red*lighting[0];rgb[1]+=green*lighting[1];rgb[2]+=blue*lighting[2];
                }
                // Evaluate direct lighting at the final vertex before stopping continuation.
                if (depth == snapshot.depth()) break;
                if (red + green + blue == 0) break;
                if (material.kind() != Material.Kind.DIELECTRIC &&
                        material.color().x()*red + material.color().y()*green + material.color().z()*blue == 0) break;
                float sign = hit.frontFace ? 1 : -1;
                float nx = hit.nx*sign, ny = hit.ny*sign, nz = hit.nz*sign;
                if (material.kind() == Material.Kind.DIELECTRIC) {
                    if(material.delta(incident,exit)) material.sampleDielectric(dx,dy,dz,nx,ny,nz,incident,exit,sampler.next(),scattering);
                    else material.scatter(dx,dy,dz,nx,ny,nz,incident,exit,sampler.next(),sampler.next(),sampler.next(),scattering);
                    if(scattering.red+scattering.green+scattering.blue==0)break;
                    if (scattering.transmitted) {
                        dielectricTransmissions++;
                        if (hit.frontFace) media[mediumCount++] = hit.object;
                        else if (mediumCount > 0) mediumCount--;
                    } else dielectricReflections++;
                } else if(material.kind()==Material.Kind.MIRROR && material.roughness()>0)
                    material.scatter(dx,dy,dz,nx,ny,nz,incident,exit,sampler.next(),sampler.next(),sampler.next(),scattering);
                else material.sample(dx, dy, dz, nx, ny, nz, sampler.next(), sampler.next(), scattering);
                if(scattering.red+scattering.green+scattering.blue==0)break;
                if(material.kind()!=Material.Kind.DIFFUSE && !scattering.delta) roughEvents++;
                previousDelta=scattering.delta;previousPdf=scattering.probability;
                if(!(scattering.transmitted && scattering.delta && incident==exit))volumeNee=volumeMode && !scattering.delta;
                previousX=hit.x;previousY=hit.y;previousZ=hit.z;
                red *= scattering.red; green *= scattering.green; blue *= scattering.blue;
                dx = scattering.dx; dy = scattering.dy; dz = scattering.dz;
                float offset = scattering.transmitted ? -BIAS : BIAS;
                ox = hit.x + nx*offset; oy = hit.y + ny*offset; oz = hit.z + nz*offset;
                continuationRays++;
            }
        }

        private void volumeLight(float x,float y,float z,float dx,float dy,float dz,Material medium,
                                 PointLight[] lights,float[] rgb,Sampler sampler) {
            rgb[0]=rgb[1]=rgb[2]=0;
            for(var light:lights) {
                float lx=light.position().x()-x,ly=light.position().y()-y,lz=light.position().z()-z;
                float d2=lx*lx+ly*ly+lz*lz;
                if(d2<BIAS*BIAS || light.intensity()==0)continue;
                float distance=(float)Math.sqrt(d2);lx/=distance;ly/=distance;lz/=distance;
                shadowRays++;
                if(!volumeVisibility(x,y,z,lx,ly,lz,distance,null)){shadowsOccluded++;continue;}
                float weight=Volume.phase(dx*lx+dy*ly+dz*lz,medium.anisotropy())*light.intensity()/d2;
                rgb[0]+=weight*light.color().x()*transmission[0];
                rgb[1]+=weight*light.color().y()*transmission[1];
                rgb[2]+=weight*light.color().z()*transmission[2];
            }
            for(var emitter:emitters) {
                areaLightSamples++;
                float u=sampler.next(),v=sampler.next();
                float ex=emitter.origin.x()+emitter.edge1.x()*u+emitter.edge2.x()*v;
                float ey=emitter.origin.y()+emitter.edge1.y()*u+emitter.edge2.y()*v;
                float ez=emitter.origin.z()+emitter.edge1.z()*u+emitter.edge2.z()*v;
                float lx=ex-x,ly=ey-y,lz=ez-z,distance=(float)Math.sqrt(lx*lx+ly*ly+lz*lz);
                if(distance<BIAS)continue;
                float pdf=emitter.pdf(x,y,z,ex,ey,ez);if(pdf<=0)continue;
                lx/=distance;ly/=distance;lz/=distance;
                shadowRays++;
                if(!volumeVisibility(x,y,z,lx,ly,lz,distance-BIAS,emitter.object)){shadowsOccluded++;continue;}
                // NEE-only at volume vertices, including final depth. No competing emitter-hit term.
                float weight=Volume.phase(dx*lx+dy*ly+dz*lz,medium.anisotropy())/pdf;
                var emission=emitter.object.primitives[0].material.emission();
                rgb[0]+=weight*emission.x()*transmission[0];
                rgb[1]+=weight*emission.y()*transmission[1];
                rgb[2]+=weight*emission.z()*transmission[2];
            }
        }
        /** Straight connections cross only smooth index-matched boundaries; refractive interfaces block. */
        boolean volumeVisibility(float ox,float oy,float oz,float dx,float dy,float dz,float distance,PreparedObject target) {
            int count=mediaAt(ox,oy,oz,visibilityMedia,visibilityDistances,visibilityHit);
            transmission[0]=transmission[1]=transmission[2]=1;
            PreparedPrimitive source=null;
            for(int crossing=0;crossing<visibilityCrossingLimit;crossing++) {
                boolean found=nearestHit(ox,oy,oz,dx,dy,dz,visibilityHit,distance,source,target,true);
                float segment=found?visibilityHit.distance:distance;
                Material current=count==0?null:visibilityMedia[count-1].primitives[0].material;
                if(current!=null) {
                    volumeVisibilitySegments++;
                    transmission[0]*=(float)Math.exp(-(current.absorption().x()+current.scattering())*segment);
                    transmission[1]*=(float)Math.exp(-(current.absorption().y()+current.scattering())*segment);
                    transmission[2]*=(float)Math.exp(-(current.absorption().z()+current.scattering())*segment);
                }
                if(!found)return true;
                var material=visibilityHit.primitive.material;
                float incident=current==null?1:current.ior();
                float exit=visibilityHit.frontFace?material.ior():count<2?1:visibilityMedia[count-2].primitives[0].material.ior();
                if(material.kind()!=Material.Kind.DIELECTRIC || incident!=exit || !material.delta(incident,exit))return false;
                if(visibilityHit.frontFace)visibilityMedia[count++]=visibilityHit.object;
                else if(count>0)count--;
                // Advance minimally along the straight ray (no new scattering or depth event).
                float step=visibilityHit.distance+1e-4f;
                distance-=step;if(distance<=0)return true;
                ox+=dx*step;oy+=dy*step;oz+=dz*step;source=visibilityHit.primitive;
            }
            return false;
        }
        /** Lighting fast path preserves the surface-only renderer when scattering is disabled. */
        private boolean visible(float ox,float oy,float oz,float dx,float dy,float dz,float distance,
                                PreparedPrimitive source,PreparedObject target,Material medium) {
            if(volumeMode)return volumeVisibility(ox,oy,oz,dx,dy,dz,distance,target);
            if(occluded(ox,oy,oz,dx,dy,dz,distance,source,target))return false;
            distance+=BIAS; // Surface-only reference attenuates to the unoffset light distance.
            transmission[0]=medium==null?1:(float)Math.exp(-medium.absorption().x()*distance);
            transmission[1]=medium==null?1:(float)Math.exp(-medium.absorption().y()*distance);
            transmission[2]=medium==null?1:(float)Math.exp(-medium.absorption().z()*distance);
            return true;
        }
        float[] visibilityTransmission() {return transmission.clone();} // Test adapter, outside hot path.
        private Material outgoingMedium(Hit hit,boolean transmission,Material current,PreparedObject[] media,int count) {
            return !transmission?current:hit.frontFace?hit.primitive.material:count<2?null:media[count-2].primitives[0].material;
        }
        private void areaLight(Hit hit,float[] rgb,Material medium,PreparedObject[] media,int count,
                               float dx,float dy,float dz,float incident,float exit,Sampler sampler,Material.Sample evaluation,boolean canContinue) {
            rgb[0]=rgb[1]=rgb[2]=0;
            float sign=hit.frontFace?1:-1,nx=hit.nx*sign,ny=hit.ny*sign,nz=hit.nz*sign;
            for(var emitter:emitters) {
                if(emitter.object==hit.object)continue;
                areaLightSamples++;
                float u=sampler.next(),v=sampler.next();
                float x=emitter.origin.x()+emitter.edge1.x()*u+emitter.edge2.x()*v;
                float y=emitter.origin.y()+emitter.edge1.y()*u+emitter.edge2.y()*v;
                float z=emitter.origin.z()+emitter.edge1.z()*u+emitter.edge2.z()*v;
                float lx=x-hit.x,ly=y-hit.y,lz=z-hit.z,distance=(float)Math.sqrt(lx*lx+ly*ly+lz*lz);
                if(distance<BIAS)continue;
                lx/=distance;ly/=distance;lz/=distance;
                float pdf=emitter.pdf(hit.x,hit.y,hit.z,x,y,z);if(pdf<=0)continue;
                hit.primitive.material.evaluate(dx,dy,dz,lx,ly,lz,nx,ny,nz,incident,exit,evaluation);
                if(evaluation.red+evaluation.green+evaluation.blue==0)continue;
                boolean transmission=nx*lx+ny*ly+nz*lz<0;
                float offset=transmission?-BIAS:BIAS;
                shadowRays++;
                var absorption=outgoingMedium(hit,transmission,medium,media,count);
                if(!visible(hit.x+nx*offset,hit.y+ny*offset,hit.z+nz*offset,lx,ly,lz,distance-BIAS,hit.primitive,emitter.object,absorption)) {shadowsOccluded++;continue;}
                float weight=(!volumeMode && canContinue?mis(pdf,evaluation.probability):1)/pdf;
                var emission=emitter.object.primitives[0].material.emission();
                rgb[0]+=evaluation.red*emission.x()*weight*this.transmission[0];
                rgb[1]+=evaluation.green*emission.y()*weight*this.transmission[1];
                rgb[2]+=evaluation.blue*emission.z()*weight*this.transmission[2];
            }
        }
        private void roughPointLight(Hit hit,PointLight[] lights,float[] rgb,Material medium,PreparedObject[] media,int count,
                                     float dx,float dy,float dz,float incident,float exit,Material.Sample evaluation) {
            rgb[0]=rgb[1]=rgb[2]=0;
            float sign=hit.frontFace?1:-1,nx=hit.nx*sign,ny=hit.ny*sign,nz=hit.nz*sign;
            for(var light:lights) {
                float lx=light.position().x()-hit.x,ly=light.position().y()-hit.y,lz=light.position().z()-hit.z;
                float d2=lx*lx+ly*ly+lz*lz;if(d2<BIAS*BIAS || light.intensity()==0)continue;
                float distance=(float)Math.sqrt(d2);lx/=distance;ly/=distance;lz/=distance;
                hit.primitive.material.evaluate(dx,dy,dz,lx,ly,lz,nx,ny,nz,incident,exit,evaluation);
                if(evaluation.red+evaluation.green+evaluation.blue==0)continue;
                boolean transmission=nx*lx+ny*ly+nz*lz<0;
                float offset=transmission?-BIAS:BIAS;
                shadowRays++;
                var absorption=outgoingMedium(hit,transmission,medium,media,count);
                if(!visible(hit.x+nx*offset,hit.y+ny*offset,hit.z+nz*offset,lx,ly,lz,distance-BIAS,hit.primitive,null,absorption)) {shadowsOccluded++;continue;}
                float weight=light.intensity()/d2;
                rgb[0]+=evaluation.red*light.color().x()*weight*this.transmission[0];
                rgb[1]+=evaluation.green*light.color().y()*weight*this.transmission[1];
                rgb[2]+=evaluation.blue*light.color().z()*weight*this.transmission[2];
            }
        }
        private void light(Hit hit, PointLight[] lights, float[] rgb, Material medium) {
            float red = 0, green = 0, blue = 0;
            float sign = hit.frontFace ? 1 : -1;
            float nx = hit.nx*sign, ny = hit.ny*sign, nz = hit.nz*sign;
            for (var light : lights) {
                float lx = light.position().x() - hit.x;
                float ly = light.position().y() - hit.y;
                float lz = light.position().z() - hit.z;
                float distanceSquared = lx*lx + ly*ly + lz*lz;
                if (distanceSquared < BIAS*BIAS || light.intensity() == 0) continue;
                float distance = (float) Math.sqrt(distanceSquared);
                lx /= distance; ly /= distance; lz /= distance;
                float cosine = nx*lx + ny*ly + nz*lz;
                if (cosine <= 0) continue;
                shadowRays++;
                if (!visible(hit.x + nx*BIAS, hit.y + ny*BIAS, hit.z + nz*BIAS,
                        lx, ly, lz, distance - BIAS, hit.primitive,null,medium)) {
                    shadowsOccluded++;
                    continue;
                }
                // Intensity is radiant intensity per steradian. Lambertian BRDF is reflectance/pi.
                float weight = cosine * light.intensity() / ((float) Math.PI * distanceSquared);
                var color = hit.primitive.material.color();
                float ar=transmission[0], ag=transmission[1], ab=transmission[2];
                red += weight * color.x() * light.color().x() * ar;
                green += weight * color.y() * light.color().y() * ag;
                blue += weight * color.z() * light.color().z() * ab;
            }
            rgb[0] = red; rgb[1] = green; rgb[2] = blue;
        }

        boolean nearestHit(float ox, float oy, float oz, float dx, float dy, float dz, Hit hit) {
            return nearestHit(ox,oy,oz,dx,dy,dz,hit,Float.POSITIVE_INFINITY,null,null,false);
        }
        private boolean nearestHit(float ox,float oy,float oz,float dx,float dy,float dz,Hit hit,
                                   float limit,PreparedPrimitive source,PreparedObject target,boolean visibility) {
            float distance = limit;
            PreparedPrimitive nearest = null;
            PreparedObject nearestObject = null;
            for (var object : objects) {
                if(object==target)continue;
                if (snapshot.acceleration() && object.primitives.length > 1 && !object.overlaps(ox, oy, oz, dx, dy, dz, distance)) continue;
                var bvh=snapshot.acceleration()?object.bvh:null;
                for(int node=0;node<(bvh==null?1:bvh.nodes.length);) {
                    int from=0,to=object.primitives.length;
                    if(bvh!=null) {
                        var n=bvh.nodes[node];
                        if(!n.bounds.overlaps(ox,oy,oz,dx,dy,dz,distance)){node=n.escape;continue;}
                        node++;if(!n.leaf())continue;from=n.from;to=n.to;
                    }else node++;
                    for(int i=from;i<to;i++) {
                        var primitive=object.primitives[bvh==null?i:bvh.order[i]];
                        if(primitive==source && primitive.flat!=null)continue;
                        if(visibility)shadowTests++;else if (continuation) continuationTests++; else primaryTests++;
                        float t = primitive.distance(ox, oy, oz, dx, dy, dz);
                        // Preserve original primitive order for exact shared-edge ties, independent of BVH order.
                        if (t < distance || (t==distance && nearestObject==object && primitive.primitiveId<nearest.primitiveId))
                            { distance = t; nearest = primitive; nearestObject = object; }
                    }
                }
            }
            if (nearest == null) return false;
            hit.primitive = nearest;
            hit.object = nearestObject;
            hit.distance = distance;
            hit.x = ox + dx*distance; hit.y = oy + dy*distance; hit.z = oz + dz*distance;
            nearest.normal(hit.x, hit.y, hit.z, hit);
            hit.frontFace = hit.nx*dx + hit.ny*dy + hit.nz*dz < 0;
            return true;
        }

        /** Closed, nonintersecting boundaries: the nearest forward crossing faces out iff inside.
         * Sort containing solids by exit distance, outermost first. Done once per camera batch. */
        private PreparedObject[] mediaAt(float x, float y, float z) {
            var media = new PreparedObject[objects.length];
            var distances = new float[objects.length];
            int count=mediaAt(x,y,z,media,distances,new Hit());
            return java.util.Arrays.copyOf(media,count);
        }
        private int mediaAt(float x,float y,float z,PreparedObject[] media,float[] distances,Hit hit) {
            int count=0;
            for (var object : objects) {
                if (object.primitives.length == 0 || object.primitives[0].material.kind() != Material.Kind.DIELECTRIC) continue;
                if(snapshot.acceleration() && !object.containsBounds(x,y,z))continue;
                float distance=Float.POSITIVE_INFINITY; PreparedPrimitive nearest=null;
                for (var primitive : object.primitives) {
                    if(hit==visibilityHit)shadowTests++; // Include containment work in exact visibility tests.
                    float t=primitive.distance(x,y,z,0,0,1);
                    if (t<distance) {distance=t;nearest=primitive;}
                }
                if (nearest == null) continue;
                nearest.normal(x,y,z+distance,hit);
                if (hit.nz <= 0) continue;
                int index=count++;
                while(index>0 && distances[index-1]<distance) {
                    media[index]=media[index-1];distances[index]=distances[index-1];index--;
                }
                media[index]=object;distances[index]=distance;
            }
            return count;
        }

        boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz,
                                 float maxDistance, PreparedPrimitive source) {
            return occluded(ox,oy,oz,dx,dy,dz,maxDistance,source,null);
        }
        private boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz,
                                 float maxDistance, PreparedPrimitive source, PreparedObject target) {
            for (var object : objects) {
                if(object==target)continue; // Sampled endpoint is not a blocker; all other emitters remain opaque.
                if (snapshot.acceleration() && object.primitives.length > 1 && !object.overlaps(ox, oy, oz, dx, dy, dz, maxDistance)) continue;
                var bvh=snapshot.acceleration()?object.bvh:null;
                for(int node=0;node<(bvh==null?1:bvh.nodes.length);) {
                    int from=0,to=object.primitives.length;
                    if(bvh!=null) {
                        var n=bvh.nodes[node];
                        if(!n.bounds.overlaps(ox,oy,oz,dx,dy,dz,maxDistance)){node=n.escape;continue;}
                        node++;if(!n.leaf())continue;from=n.from;to=n.to;
                    }else node++;
                    for(int i=from;i<to;i++) {
                        var primitive=object.primitives[bvh==null?i:bvh.order[i]];
                        // Only flat source primitives can be skipped. A sphere can occlude its own interior rays.
                        if (primitive == source && primitive.flat != null) continue;
                        shadowTests++;
                        if (primitive.distance(ox, oy, oz, dx, dy, dz) < maxDistance) return true;
                    }
                }
            }
            return false;
        }
    }

}
