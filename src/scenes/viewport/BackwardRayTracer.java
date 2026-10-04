package scenes.viewport;

import logging.LogManager;
import logging.Logger;
import math.Intersection;
import math.Ray;
import profiling.RuntimeMetrics;
import profiling.TraceProfile;
import scenes.viewport.lights.PointLight;

/**
 * Pinhole-camera backward ray tracer. For each sensor pixel, shoots a primary ray from the eye through
 * the pixel into the scene. On the nearest surface hit, performs direct-lighting (shadow ray + Lambertian
 * cosine term) for every {@link PointLight}. Produces a complete image in one pass — no accumulation needed.
 *
 * <p>Surface normals are flipped to face the camera ray so that Lambertian shading works regardless of
 * the object's geometric vertex order.
 *
 * <p>Per-trace counters ({@link #primaryRays()}, {@link #primaryHits()}, {@link #shadowRays()},
 * {@link #shadowsOccluded()}, {@link #litPixels()}, {@link #traceNanos()}) are updated on every
 * {@link #trace()} call. A one-line INFO summary is emitted at most once per {@value #LOG_INTERVAL_MS} ms
 * so the AWT 144 Hz render loop doesn't spam the log.
 */
public class BackwardRayTracer {
    private static final Logger LOG               = LogManager.instance().getThis();
    private static final float  SHADOW_BIAS       = 1e-3f;
    private static final long   LOG_INTERVAL_MS   = 1000;
    private static final long   LOG_INTERVAL_NS   = LOG_INTERVAL_MS * 1_000_000L;

    private final ViewportState state;

    private int  primaryRays;
    private int  primaryHits;
    private int  shadowRays;
    private int  shadowsOccluded;
    private int  litPixels;
    private long traceNanos;
    private TraceProfile.Stats profile;
    public TraceProfile.Stats profile() { return profile; }

    // Initialised so the first call to maybeLog() always emits. (Using Long.MIN_VALUE here would
    // overflow on subtraction with a positive nanoTime() and silently suppress every log forever.)
    private long lastLogNanos;

    public BackwardRayTracer(ViewportState state) {
        this.state = state;
        this.lastLogNanos = System.nanoTime() - LOG_INTERVAL_NS;
    }

    public int  primaryRays()     { return primaryRays; }
    public int  primaryHits()     { return primaryHits; }
    public int  shadowRays()      { return shadowRays; }
    public int  shadowsOccluded() { return shadowsOccluded; }
    public int  litPixels()       { return litPixels; }
    public long traceNanos()      { return traceNanos; }

    public float[][] trace() {
        primaryRays = primaryHits = shadowRays = shadowsOccluded = litPixels = 0;
        long cpuStart = RuntimeMetrics.threadCpu(), bytesStart = RuntimeMetrics.allocatedBytes();
        long t0 = System.nanoTime();

        var sensor = state.cameraSensor();
        var eye = state.eye();
        var surfaces = state.objects().stream().map(TraceSurface::new).toArray(TraceSurface[]::new);
        int W = state.sensorPixelsW();
        int H = state.sensorPixelsH();
        var buf = new float[H][W];
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                float u = (px + 0.5f) / W;
                float v = (py + 0.5f) / H;
                var pixelWorld = sensor.origin()
                        .add(sensor.edge1().scale(u))
                        .add(sensor.edge2().scale(v));
                var dir = pixelWorld.sub(eye).normalized();
                primaryRays++;
                float lit = shade(new Ray(eye, dir), surfaces);
                if (lit > 0) {
                    litPixels++;
                }
                buf[py][px] = lit;
            }
        }

        traceNanos = System.nanoTime() - t0;
        long cpu = RuntimeMetrics.delta(cpuStart, RuntimeMetrics.threadCpu());
        long bytes = RuntimeMetrics.delta(bytesStart, RuntimeMetrics.allocatedBytes());
        profile = new TraceProfile.Stats(cpu, bytes, (long) primaryRays * surfaces.length);
        maybeLog();
        return buf;
    }

    /** Returns the accumulated light contribution at the first surface hit by {@code ray}. */
    public float shade(Ray ray) {
        return shade(ray, state.objects().stream().map(TraceSurface::new).toArray(TraceSurface[]::new));
    }

    private float shade(Ray ray, TraceSurface[] surfaces) {
        var hit = nearestHit(ray, surfaces);
        return light(ray, hit, surfaces);
    }

    private float light(Ray ray, Intersection hit, TraceSurface[] surfaces) {
        if (hit == null) {
            return 0;
        }
        primaryHits++;
        var n = hit.normal();
        if (n.dot(ray.direction()) > 0) {
            n = n.negate();
        }
        float lit = 0;
        for (var light : state.lights()) {
            if (!(light instanceof PointLight pl)) {
                continue;
            }
            var toLight = pl.position().sub(hit.point());
            float lightDist = toLight.length();
            if (lightDist < SHADOW_BIAS) {
                continue;
            }
            var lightDir = toLight.scale(1f / lightDist);
            var shadowOrigin = hit.point().add(lightDir.scale(SHADOW_BIAS));
            shadowRays++;
            if (occluded(new Ray(shadowOrigin, lightDir), lightDist - SHADOW_BIAS, surfaces)) {
                shadowsOccluded++;
                continue;
            }
            float cosTheta = Math.max(0, n.dot(lightDir));
            lit += cosTheta;
        }
        return lit;
    }

    private Intersection nearestHit(Ray ray, TraceSurface[] surfaces) {
        TraceSurface nearest = null;
        float distance = Float.POSITIVE_INFINITY;
        for (var surface : surfaces) {
            float t = surface.distance(ray);
            if (t < distance) {
                nearest = surface;
                distance = t;
            }
        }
        return nearest == null ? null : nearest.hit(ray, distance);
    }

    private boolean occluded(Ray ray, float maxDist, TraceSurface[] surfaces) {
        for (var surface : surfaces) {
            if (surface.distance(ray) < maxDist) {
                return true;
            }
        }
        return false;
    }

    private void maybeLog() {
        long now = System.nanoTime();
        if (now - lastLogNanos < LOG_INTERVAL_NS) {
            return;
        }
        lastLogNanos = now;
        double ms = traceNanos / 1e6;
        double mraysPerSec = primaryRays / (traceNanos / 1e3);  // rays / microsecond == Mrays/s
        LOG.info("trace: %d rays in %.2f ms (%.1f Mrays/s) — %d hits, %d/%d shadow rays occluded, %d lit pixels",
                primaryRays, ms, mraysPerSec, primaryHits, shadowsOccluded, shadowRays, litPixels);
    }
}
