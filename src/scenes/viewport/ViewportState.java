package scenes.viewport;

import math.Vec3;
import scenes.viewport.lights.Light;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.SceneObject;

import java.util.ArrayList;
import java.util.List;

public class ViewportState {
    private       Rect              cameraSensor;
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
        if (size < 64 || size > 1600) throw new IllegalArgumentException("Sensor size must be 64..1600");
        sensorPixelsW = sensorPixelsH = size;
        accumulator = new float[size][size];
    }

    public ViewportState(Rect cameraSensor, int sensorPixelsW, int sensorPixelsH) {
        this.cameraSensor = cameraSensor;
        this.sensorPixelsW = sensorPixelsW;
        this.sensorPixelsH = sensorPixelsH;
        this.accumulator = new float[sensorPixelsH][sensorPixelsW];
    }

    public float[][] accumulator() { return accumulator; }

    public void clearAccumulator() {
        for (var row : accumulator) {
            java.util.Arrays.fill(row, 0);
        }
    }

    public Rect cameraSensor() { return cameraSensor; }
    public void cameraSensor(Rect r) { this.cameraSensor = r; }
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
