package engine;

import engine.lights.Light;
import engine.objects.Rect;
import engine.objects.RenderPrimitive;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

public class ViewportState {
    /** Immutable estimator identity; display and scheduling settings intentionally excluded. */
    public record RenderKey(
            List<SceneInstance> instances,
            List<RenderPrimitive> objects,
            List<Light> lights,
            Camera.Identity camera,
            int width,
            int height,
            int depth,
            long seed,
            long restart) {
        public Vec3 eye() {
            return camera.eye();
        }

        public Rect sensor() {
            return camera.sensor();
        }

        /**
         * Camera-only differences can finish as a coherent preview, never as current accumulation.
         */
        public boolean sameTransport(RenderKey other) {
            return other != null
                    && instances.equals(other.instances)
                    && objects.equals(other.objects)
                    && lights.equals(other.lights)
                    && width == other.width
                    && height == other.height
                    && depth == other.depth
                    && seed == other.seed
                    && restart == other.restart;
        }
    }

    public RenderKey renderKey() {
        return new RenderKey(
                List.copyOf(instances),
                List.copyOf(objects),
                List.copyOf(lights),
                camera.identity(),
                sampledWidth(),
                sampledHeight(),
                pathDepth,
                seed,
                restartVersion);
    }

    /** Caller holds this state's monitor. The copy belongs exclusively to the trace coordinator. */
    public ViewportState renderSnapshot() {
        var copy = new ViewportState(camera.sensor(), sampledWidth(), sampledHeight(), false);
        copy.camera = camera;
        copy.cameraHistoryVersion = cameraHistoryVersion;
        copy.instances.addAll(instances);
        copy.objects.addAll(objects);
        copy.lights.addAll(lights);
        copy.focusSceneRevision = focusSceneRevision;
        copy.pathDepth = pathDepth;
        copy.seed = seed;
        copy.restartVersion = restartVersion;
        copy.preset = preset;
        copy.samplesPerFrame = samplesPerFrame;
        copy.sampleTarget = sampleTarget;
        copy.paused = paused;
        copy.acceleration = acceleration;
        copy.workers = workers;
        copy.tileSize = tileSize;
        copy.temporal = temporal;
        copy.temporalVersion = temporalVersion;
        copy.temporalBudget = temporalBudget;
        copy.motionSamples = motionSamples;
        copy.motionScale = motionScale;
        return copy;
    }

    private Camera camera;
    private FocusController focusController;

    public FocusController focusController() {
        if (focusController == null) focusController = new FocusController(this);
        return focusController;
    }

    public String focusStatus() {
        return focusController().status();
    }

    private long cameraHistoryVersion;

    public long cameraHistoryVersion() {
        return cameraHistoryVersion;
    }

    private float presentationAspect;
    private int sensorPixelsW;
    private int sensorPixelsH;
    // Requested grid stays authoritative. Only the async controller chooses a temporary grid.
    private int sampledW, sampledH;
    private boolean interactive;
    private boolean temporal;
    private long temporalVersion;
    private boolean temporalBudget;
    private int motionSamples = 1;
    private double motionScale = 1;

    public boolean temporalBudget() {
        return temporalBudget;
    }

    public void temporalBudget(boolean value) {
        temporalBudget = value;
    }

    public int motionSamples() {
        return motionSamples;
    }

    public void motionSamples(int value) {
        if (value < 1 || value > 8)
            throw new IllegalArgumentException("Motion samples must be 1..8");
        motionSamples = value;
    }

    public double motionScale() {
        return motionScale;
    }

    public void motionScale(double value) {
        if (!Double.isFinite(value) || value < .25 || value > 1)
            throw new IllegalArgumentException("Motion scale must be 0.25..1");
        motionScale = value;
    }

    boolean temporalBudgetSupported() {
        return temporalEffective()
                && temporalBudget
                && instances.stream().noneMatch(o -> o.material().scattering() > 0)
                && (pathDepth > 0 || instances.stream().anyMatch(o -> o.material().emissive()))
                && instances.stream()
                        .anyMatch(
                                o ->
                                        o.material().kind() == Material.Kind.DIFFUSE
                                                && !o.material().emissive());
    }

    public String temporalBudgetStatus() {
        return "motion budget="
                + (temporalBudget ? "on" : "off")
                + " samples="
                + motionSamples
                + " scale="
                + String.format(java.util.Locale.ROOT, "%.2f", motionScale)
                + (temporalBudget && !temporalBudgetSupported()
                        ? (camera.temporalSupported()
                                ? " (inactive: needs temporal diffuse, no volumes)"
                                : " (inactive: finite aperture)")
                        : "");
    }

    int movingBatch(boolean moving) {
        if (moving && interactive) return 1;
        return moving && temporalBudgetSupported()
                ? Math.min(samplesPerFrame, motionSamples)
                : samplesPerFrame;
    }

    /** Presentation reconstruction only; deliberately excluded from the raw estimator key. */
    public boolean temporal() {
        return temporal;
    }

    public boolean temporalEffective() {
        return temporal && camera.temporalSupported();
    }

    public String temporalStatus() {
        return !temporal
                ? "temporal=off (raw)"
                : !camera.temporalSupported()
                        ? "temporal=on (inactive: finite aperture; raw)"
                        : instances.stream().anyMatch(o -> o.material().scattering() > 0)
                                ? "temporal=on (inactive: volume scattering; raw)"
                                : "temporal=on";
    }

    public void temporal(boolean enabled) {
        if (temporal != enabled) {
            temporal = enabled;
            temporalVersion++;
        }
    }

    void temporal(boolean enabled, long revision) {
        temporal = enabled;
        temporalVersion = revision * 2 + (enabled ? 1 : 0);
    }

    public long temporalVersion() {
        return temporalVersion;
    }

    private double interactiveMillis = 1000. / 60;
    private int minimumW, minimumH;

    public boolean interactive() {
        return interactive;
    }

    public void interactive(boolean enabled) {
        interactive = enabled;
    }

    public double interactiveMillis() {
        return interactiveMillis;
    }

    public void interactiveMillis(double value) {
        if (!Double.isFinite(value) || value < 1 || value > 1000)
            throw new IllegalArgumentException("Interactive target must be 1..1000 milliseconds");
        interactiveMillis = value;
    }

    public void interactiveMinimum(int width, int height) {
        if (width < 64 || width > 1600 || height < 64 || height > 1600)
            throw new IllegalArgumentException("Minimum dimensions must each be 64..1600");
        minimumW = width;
        minimumH = height;
    }

    void interactiveMinimumDefault() {
        minimumW = minimumH = 0;
    }

    double minimumScale() {
        // Default quarter grid, respecting the existing 64-pixel minimum on both axes.
        double scale =
                minimumW == 0
                        ? .25
                        : Math.max(
                                (double) minimumW / sensorPixelsW,
                                (double) minimumH / sensorPixelsH);
        return Math.min(1, Math.max(scale, Math.max(64. / sensorPixelsW, 64. / sensorPixelsH)));
    }

    public int sampledWidth() {
        return sampledW == 0 ? sensorPixelsW : sampledW;
    }

    public int sampledHeight() {
        return sampledH == 0 ? sensorPixelsH : sampledH;
    }

    /** Called under the live monitor between jobs; no pixel storage is allocated here. */
    void sampledResolution(int width, int height) {
        sampledW = width;
        sampledH = height;
    }

    public String interactiveStatus() {
        return "interactive="
                + (interactive ? "on" : "off")
                + " target="
                + String.format(java.util.Locale.ROOT, "%.2f", interactiveMillis)
                + "ms min="
                + Math.round(sensorPixelsW * minimumScale())
                + "x"
                + Math.round(sensorPixelsH * minimumScale())
                + " max="
                + sensorPixelsW
                + "x"
                + sensorPixelsH
                + " sampled="
                + sampledWidth()
                + "x"
                + sampledHeight();
    }

    private long focusSceneRevision;

    long focusSceneRevision() {
        return focusSceneRevision;
    }

    /** Track edits even when a scene is edited and restored before the next display tick. */
    private final class QueryList<E> extends java.util.AbstractList<E> {
        private final ArrayList<E> values = new ArrayList<>();
        private final java.util.function.BiPredicate<E, E> equivalent;

        QueryList(java.util.function.BiPredicate<E, E> equivalent) {
            this.equivalent = equivalent;
        }

        @Override
        public E get(int i) {
            return values.get(i);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public E set(int i, E value) {
            var old = values.set(i, value);
            if (!equivalent.test(old, value)) focusSceneRevision++;
            return old;
        }

        @Override
        public void add(int i, E value) {
            values.add(i, value);
            focusSceneRevision++;
            modCount++;
        }

        @Override
        public E remove(int i) {
            var old = values.remove(i);
            focusSceneRevision++;
            modCount++;
            return old;
        }
    }

    private final List<RenderPrimitive> objects = new QueryList<>((a, b) -> a == b);
    private final List<Light> lights = new ArrayList<>();
    private final List<SceneInstance> instances =
            new QueryList<>(
                    (a, b) ->
                            a.name().equals(b.name())
                                    && a.geometry() == b.geometry()
                                    && a.transform() == b.transform());
    private float exposure;
    private String preset = "custom";
    // Active backward path settings are independent of the legacy forward tracer's maxBounces.
    private int pathDepth, samplesPerFrame = 1;
    private long seed = 1, restartVersion, sampleTarget, accumulatedSamples;
    private boolean paused;
    private boolean acceleration = true;
    private int workers =
            Integer.getInteger(
                    "renderer.workers",
                    Math.min(14, Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
    private int tileSize = Integer.getInteger("renderer.tile", 32);

    public int workers() {
        return workers;
    }

    public void workers(int value) {
        if (value < 1 || value > Math.min(32, Runtime.getRuntime().availableProcessors()))
            throw new IllegalArgumentException(
                    "Workers must be 1.."
                            + Math.min(32, Runtime.getRuntime().availableProcessors()));
        workers = value;
    }

    public int tileSize() {
        return tileSize;
    }

    public void tileSize(int value) {
        if (value < 1 || value > 256)
            throw new IllegalArgumentException("Tile size must be 1..256");
        tileSize = value;
    }

    public boolean acceleration() {
        return acceleration;
    }

    public void acceleration(boolean enabled) {
        if (acceleration != enabled) {
            acceleration = enabled;
            restart();
        }
    }

    public static final long SAMPLE_LIMIT = 1_000_000_000L;

    public int pathDepth() {
        return pathDepth;
    }

    public void pathDepth(int n) {
        if (n < 0 || n > 32)
            throw new IllegalArgumentException("Depth must be 0..32 continuations");
        pathDepth = n;
    }

    public int samplesPerFrame() {
        return samplesPerFrame;
    }

    public void samplesPerFrame(int n) {
        if (n < 1 || n > 8) throw new IllegalArgumentException("Samples/frame must be 1..8");
        samplesPerFrame = n;
    }

    public long seed() {
        return seed;
    }

    public void seed(long n) {
        seed = n;
    }

    public void restart() {
        restartVersion++;
    }

    public long restartVersion() {
        return restartVersion;
    }

    public long sampleTarget() {
        return sampleTarget;
    }

    public void sampleTarget(long n) {
        if (n < 0 || n > SAMPLE_LIMIT)
            throw new IllegalArgumentException("Target must be 0 (continuous)..1000000000 spp");
        sampleTarget = n;
    }

    public boolean paused() {
        return paused;
    }

    public void paused(boolean value) {
        if (paused == value) return;
        if (focusController != null) focusController.tick();
        paused = value;
        if (focusController != null) focusController.pauseChanged();
    }

    public long accumulatedSamples() {
        return accumulatedSamples;
    }

    public void accumulatedSamples(long n) {
        accumulatedSamples = n;
    }

    public long effectiveTarget() {
        return sampleTarget == 0 ? SAMPLE_LIMIT : sampleTarget;
    }

    public String samplingStatus() {
        return paused
                ? "paused"
                : accumulatedSamples >= effectiveTarget() ? "complete" : "converging";
    }

    public List<SceneInstance> instances() {
        return instances;
    }

    public String preset() {
        return preset;
    }

    public void preset(String name) {
        preset = name;
    }

    public float exposure() {
        return exposure;
    }

    public void exposure(float stops) {
        if (!Float.isFinite(stops) || stops < -16 || stops > 16)
            throw new IllegalArgumentException("Exposure must be -16..16 stops");
        exposure = stops;
    }

    private int samplesPerLight = 1000;
    private int maxBounces = 4;
    private float[][] accumulator;

    public void resolution(int size) {
        resolution(size, size);
    }

    public void resolution(int width, int height) {
        if (width < 64 || width > 1600 || height < 64 || height > 1600)
            throw new IllegalArgumentException("Sensor width and height must each be 64..1600");
        var next = new float[height][width];
        sensorPixelsW = width;
        sensorPixelsH = height;
        sampledW = sampledH = 0;
        accumulator = next;
    }

    public ViewportState(Rect cameraSensor, int sensorPixelsW, int sensorPixelsH) {
        this(cameraSensor, sensorPixelsW, sensorPixelsH, true);
    }

    private ViewportState(
            Rect cameraSensor, int sensorPixelsW, int sensorPixelsH, boolean legacyBuffer) {
        workers(workers);
        tileSize(tileSize);
        this.camera = new Camera(new Vec3(0, 0, -1), cameraSensor);
        this.sensorPixelsW = sensorPixelsW;
        this.sensorPixelsH = sensorPixelsH;
        this.accumulator = legacyBuffer ? new float[sensorPixelsH][sensorPixelsW] : null;
    }

    public float[][] accumulator() {
        return accumulator;
    }

    public void clearAccumulator() {
        for (var row : accumulator) {
            java.util.Arrays.fill(row, 0);
        }
    }

    public Camera camera() {
        return camera;
    }

    public void camera(Camera value) {
        if (camera.effectiveMode() != value.effectiveMode()
                || camera.temporalSupported() != value.temporalSupported()) cameraHistoryVersion++;
        if (focusController != null
                && (camera.projection() != value.projection()
                        || camera.height() != value.height()
                        || Math.abs(
                                        camera.sensor().edge1().length()
                                                - value.sensor().edge1().length())
                                > camera.sensor().edge1().length() * 1e-5
                        || Math.abs(
                                        camera.sensor().edge2().length()
                                                - value.sensor().edge2().length())
                                > camera.sensor().edge2().length() * 1e-5))
            focusController.framingChanged();
        camera = value;
    }

    public Rect cameraSensor() {
        return camera.sensor();
    }

    /** Set the display aspect without changing the sampling grid or vertical field of view. */
    public void presentationAspect(float aspect) {
        if (!Float.isFinite(aspect) || aspect <= 0)
            throw new IllegalArgumentException("Aspect must be positive");
        presentationAspect = aspect;
        cameraSensor(camera.sensor());
    }

    public void cameraSensor(Rect r) {
        if (presentationAspect > 0) {
            float targetWidth = r.edge2().length() * presentationAspect;
            float currentWidth = r.edge1().length();
            // Avoid modifying already-fitted sensors on every camera movement.
            if (Math.abs(targetWidth - currentWidth) > targetWidth * 1e-6f) {
                var horizontal = r.edge1().scale(targetWidth / currentWidth);
                r =
                        new Rect(
                                r.origin().add(r.edge1().sub(horizontal).scale(.5f)),
                                horizontal,
                                r.edge2());
            }
        }
        camera(camera.withPose(camera.eye(), r));
    }

    public Vec3 eye() {
        return camera.eye();
    }

    public void eye(Vec3 e) {
        camera(camera.withPose(e, camera.sensor()));
    }

    public int sensorPixelsW() {
        return sensorPixelsW;
    }

    public int sensorPixelsH() {
        return sensorPixelsH;
    }

    public List<RenderPrimitive> objects() {
        return objects;
    }

    public List<Light> lights() {
        return lights;
    }

    public int samplesPerLight() {
        return samplesPerLight;
    }

    public void samplesPerLight(int n) {
        this.samplesPerLight = n;
    }

    public int maxBounces() {
        return maxBounces;
    }

    public void maxBounces(int n) {
        this.maxBounces = n;
    }

    public void addObject(RenderPrimitive o) {
        objects.add(o);
    }

    public void addLight(Light l) {
        lights.add(l);
    }
}
