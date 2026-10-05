package scenes.viewport;

import math.Vec3;
import scenes.viewport.lights.Light;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.SceneObject;

import java.util.ArrayList;
import java.util.List;

public class ViewportState {
    /** Immutable estimator identity; display and scheduling settings intentionally excluded. */
    public record RenderKey(List<SceneInstance> instances, List<SceneObject> objects, List<Light> lights,
                            Vec3 eye, Rect sensor, int width, int height, int depth, long seed, long restart) {
        /** Camera-only differences can finish as a coherent preview, never as current accumulation. */
        public boolean sameTransport(RenderKey other) {
            return other != null && instances.equals(other.instances) && objects.equals(other.objects)
                    && lights.equals(other.lights) && width==other.width && height==other.height
                    && depth==other.depth && seed==other.seed && restart==other.restart;
        }
    }
    public RenderKey renderKey() {
        return new RenderKey(List.copyOf(instances), List.copyOf(objects), List.copyOf(lights), eye,
                cameraSensor, sensorPixelsW, sensorPixelsH, pathDepth, seed, restartVersion);
    }
    /** Caller holds this state's monitor. The copy belongs exclusively to the trace coordinator. */
    public ViewportState renderSnapshot() {
        var copy = new ViewportState(cameraSensor, sensorPixelsW, sensorPixelsH, false);
        copy.eye=eye; copy.instances.addAll(instances); copy.objects.addAll(objects); copy.lights.addAll(lights);
        copy.pathDepth=pathDepth; copy.seed=seed; copy.restartVersion=restartVersion; copy.preset=preset;
        copy.samplesPerFrame=samplesPerFrame; copy.sampleTarget=sampleTarget; copy.paused=paused;
        copy.acceleration=acceleration; copy.workers=workers; copy.tileSize=tileSize;
        return copy;
    }
    private       Rect              cameraSensor;
    private float presentationAspect;
    private       Vec3              eye             = new Vec3(0, 0, -1);
    private int                     sensorPixelsW;
    private int                     sensorPixelsH;
    private final List<SceneObject> objects         = new ArrayList<>();
    private final List<Light>       lights          = new ArrayList<>();
    private final List<SceneInstance> instances = new ArrayList<>();
    private float exposure;
    private String preset = "custom";
    // Active backward path settings are independent of the legacy forward tracer's maxBounces.
    private int pathDepth, samplesPerFrame = 1;
    private long seed = 1, restartVersion, sampleTarget, accumulatedSamples;
    private boolean paused;
    private boolean acceleration=true;
    private int workers = Integer.getInteger("renderer.workers", Math.min(14, Math.max(1, Runtime.getRuntime().availableProcessors()-2)));
    private int tileSize = Integer.getInteger("renderer.tile", 32);
    public int workers() { return workers; }
    public void workers(int value) {
        if (value < 1 || value > Math.min(32, Runtime.getRuntime().availableProcessors()))
            throw new IllegalArgumentException("Workers must be 1.." + Math.min(32, Runtime.getRuntime().availableProcessors()));
        workers = value;
    }
    public int tileSize() { return tileSize; }
    public void tileSize(int value) {
        if (value < 1 || value > 256) throw new IllegalArgumentException("Tile size must be 1..256");
        tileSize = value;
    }
    public boolean acceleration(){return acceleration;}
    public void acceleration(boolean enabled){if(acceleration!=enabled){acceleration=enabled;restart();}}
    public static final long SAMPLE_LIMIT = 1_000_000_000L;
    public int pathDepth() { return pathDepth; }
    public void pathDepth(int n) {
        if (n < 0 || n > 32) throw new IllegalArgumentException("Depth must be 0..32 continuations");
        pathDepth = n;
    }
    public int samplesPerFrame() { return samplesPerFrame; }
    public void samplesPerFrame(int n) {
        if (n < 1 || n > 8) throw new IllegalArgumentException("Samples/frame must be 1..8");
        samplesPerFrame = n;
    }
    public long seed() { return seed; }
    public void seed(long n) { seed = n; }
    public void restart() { restartVersion++; }
    public long restartVersion() { return restartVersion; }
    public long sampleTarget() { return sampleTarget; }
    public void sampleTarget(long n) {
        if (n < 0 || n > SAMPLE_LIMIT) throw new IllegalArgumentException("Target must be 0 (continuous)..1000000000 spp");
        sampleTarget = n;
    }
    public boolean paused() { return paused; }
    public void paused(boolean value) { paused = value; }
    public long accumulatedSamples() { return accumulatedSamples; }
    void accumulatedSamples(long n) { accumulatedSamples = n; }
    public long effectiveTarget() { return sampleTarget == 0 ? SAMPLE_LIMIT : sampleTarget; }
    public String samplingStatus() {
        return paused ? "paused" : accumulatedSamples >= effectiveTarget() ? "complete" : "converging";
    }
    public List<SceneInstance> instances() { return instances; }
    public String preset() { return preset; }
    public void preset(String name) { preset = name; }
    public float exposure() { return exposure; }
    public void exposure(float stops) {
        if (!Float.isFinite(stops) || stops < -16 || stops > 16) throw new IllegalArgumentException("Exposure must be -16..16 stops");
        exposure = stops;
    }
    private       int               samplesPerLight = 1000;
    private       int               maxBounces      = 4;
    private float[][]               accumulator;
    public void resolution(int size) {
        resolution(size, size);
    }

    public void resolution(int width, int height) {
        if (width < 64 || width > 1600 || height < 64 || height > 1600)
            throw new IllegalArgumentException("Sensor width and height must each be 64..1600");
        var next = new float[height][width];
        sensorPixelsW = width;
        sensorPixelsH = height;
        accumulator = next;
    }

    public ViewportState(Rect cameraSensor, int sensorPixelsW, int sensorPixelsH) {
        this(cameraSensor, sensorPixelsW, sensorPixelsH, true);
    }
    private ViewportState(Rect cameraSensor, int sensorPixelsW, int sensorPixelsH, boolean legacyBuffer) {
        workers(workers);
        tileSize(tileSize);
        this.cameraSensor = cameraSensor;
        this.sensorPixelsW = sensorPixelsW;
        this.sensorPixelsH = sensorPixelsH;
        this.accumulator = legacyBuffer ? new float[sensorPixelsH][sensorPixelsW] : null;
    }

    public float[][] accumulator() { return accumulator; }

    public void clearAccumulator() {
        for (var row : accumulator) {
            java.util.Arrays.fill(row, 0);
        }
    }

    public Rect cameraSensor() { return cameraSensor; }
    /** Set the display aspect without changing the sampling grid or vertical field of view. */
    public void presentationAspect(float aspect) {
        if (!Float.isFinite(aspect) || aspect <= 0) throw new IllegalArgumentException("Aspect must be positive");
        presentationAspect = aspect;
        cameraSensor(cameraSensor);
    }

    public void cameraSensor(Rect r) {
        if (presentationAspect > 0) {
            float targetWidth = r.edge2().length() * presentationAspect;
            float currentWidth = r.edge1().length();
            // Avoid modifying already-fitted sensors on every camera movement.
            if (Math.abs(targetWidth - currentWidth) > targetWidth * 1e-6f) {
                var horizontal = r.edge1().scale(targetWidth / currentWidth);
                r = new Rect(r.origin().add(r.edge1().sub(horizontal).scale(.5f)), horizontal, r.edge2());
            }
        }
        this.cameraSensor = r;
    }
    public Vec3 eye() { return eye; }
    public void eye(Vec3 e) { this.eye = e; }
    public int sensorPixelsW() { return sensorPixelsW; }
    public int sensorPixelsH() { return sensorPixelsH; }
    public List<SceneObject> objects() { return objects; }
    public List<Light> lights() { return lights; }
    public int samplesPerLight() { return samplesPerLight; }
    public void samplesPerLight(int n) { this.samplesPerLight = n; }
    public int maxBounces() { return maxBounces; }
    public void maxBounces(int n) { this.maxBounces = n; }
    public void addObject(SceneObject o) { objects.add(o); }
    public void addLight(Light l) { lights.add(l); }
}
