package scenes.viewport;

import logging.LogManager;
import logging.Logger;
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
    private long shadowTests;
    public long shadowTests() { return shadowTests; }
    /** One scratch hit per trace invocation, reused for all pixels; never returned to callers. */
    private static final class Hit {
        TraceSurface surface;
        float x, y, z;
    }
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
        shadowTests = 0;
        long cpuStart = RuntimeMetrics.threadCpu(), bytesStart = RuntimeMetrics.allocatedBytes();
        long t0 = System.nanoTime();

        var sensor = state.cameraSensor();
        var eye = state.eye();
        var surfaces = state.objects().stream().map(TraceSurface::new).toArray(TraceSurface[]::new);
        var lights = pointLights(); // Snapshot once, avoiding list iterators and filtering in each pixel.
        int W = state.sensorPixelsW();
        int H = state.sensorPixelsH();
        var buf = new float[H][W];
        var hit = new Hit();
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                float u = (px + 0.5f) / W;
                float v = (py + 0.5f) / H;
                float dx = sensor.origin().x() + sensor.edge1().x() * u + sensor.edge2().x() * v - eye.x();
                float dy = sensor.origin().y() + sensor.edge1().y() * u + sensor.edge2().y() * v - eye.y();
                float dz = sensor.origin().z() + sensor.edge1().z() * u + sensor.edge2().z() * v - eye.z();
                float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length == 0) throw new ArithmeticException("Cannot normalize zero vector");
                float inverseLength = 1f / length;
                primaryRays++;
                float lit = shade(eye.x(), eye.y(), eye.z(), dx * inverseLength, dy * inverseLength,
                        dz * inverseLength, surfaces, lights, hit);
                if (lit > 0) {
                    litPixels++;
                }
                buf[py][px] = lit;
            }
        }

        traceNanos = System.nanoTime() - t0;
        long cpu = RuntimeMetrics.delta(cpuStart, RuntimeMetrics.threadCpu());
        long bytes = RuntimeMetrics.delta(bytesStart, RuntimeMetrics.allocatedBytes());
        profile = new TraceProfile.Stats(cpu, bytes, (long) primaryRays * surfaces.length, shadowTests);
        maybeLog();
        return buf;
    }

    /** Returns the accumulated light contribution at the first surface hit by {@code ray}. */
    public float shade(Ray ray) {
        var origin = ray.origin();
        var direction = ray.direction();
        return shade(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(),
                state.objects().stream().map(TraceSurface::new).toArray(TraceSurface[]::new), pointLights(), new Hit());
    }

    private PointLight[] pointLights() {
        return state.lights().stream().filter(PointLight.class::isInstance)
                .map(PointLight.class::cast).toArray(PointLight[]::new);
    }

    private float shade(float ox, float oy, float oz, float dx, float dy, float dz,
                        TraceSurface[] surfaces, PointLight[] lights, Hit scratch) {
        var hit = nearestHit(ox, oy, oz, dx, dy, dz, surfaces, scratch);
        return light(dx, dy, dz, hit, surfaces, lights);
    }

    private float light(float dx, float dy, float dz, Hit hit, TraceSurface[] surfaces, PointLight[] lights) {
        if (hit == null) {
            return 0;
        }
        primaryHits++;
        var n = hit.surface.normalAt(hit.x, hit.y, hit.z);
        float nx = n.x(), ny = n.y(), nz = n.z();
        if (nx * dx + ny * dy + nz * dz > 0) {
            nx = -nx; ny = -ny; nz = -nz;
        }
        float lit = 0;
        for (var light : lights) {
            float lx = light.position().x() - hit.x;
            float ly = light.position().y() - hit.y;
            float lz = light.position().z() - hit.z;
            float lightDist = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (lightDist < SHADOW_BIAS) {
                continue;
            }
            float inverseDistance = 1f / lightDist;
            lx *= inverseDistance; ly *= inverseDistance; lz *= inverseDistance;
            float cosTheta = Math.max(0, nx * lx + ny * ly + nz * lz);
            // A light behind the camera-facing surface contributes nothing, regardless of visibility.
            if (cosTheta <= 0) continue;
            shadowRays++;
            // With no other primitive there is nothing to query, including no shadow origin to construct.
            if ((surfaces.length > 1 || !hit.surface.flat()) && occluded(hit.x + lx * SHADOW_BIAS, hit.y + ly * SHADOW_BIAS, hit.z + lz * SHADOW_BIAS,
                    lx, ly, lz, lightDist - SHADOW_BIAS, surfaces, hit.surface)) {
                shadowsOccluded++;
                continue;
            }
            lit += cosTheta;
        }
        return lit;
    }

    private Hit nearestHit(float ox, float oy, float oz, float dx, float dy, float dz,
                           TraceSurface[] surfaces, Hit result) {
        TraceSurface nearest = null;
        float distance = Float.POSITIVE_INFINITY;
        for (var surface : surfaces) {
            float t = surface.distance(ox, oy, oz, dx, dy, dz);
            if (t < distance) {
                nearest = surface;
                distance = t;
            }
        }
        if (nearest == null) return null;
        result.surface = nearest;
        result.x = ox + dx * distance;
        result.y = oy + dy * distance;
        result.z = oz + dz * distance;
        return result;
    }

    private boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz,
                             float maxDist, TraceSurface[] surfaces, TraceSurface source) {
        for (var surface : surfaces) {
            // Skip only this primitive, not a whole mesh: other triangles must still cast shadows.
            // Tri and Rect are flat, so a departing ray cannot hit its source again.
            if (surface == source && surface.flat()) continue;
            shadowTests++;
            if (surface.distance(ox, oy, oz, dx, dy, dz) < maxDist) {
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
