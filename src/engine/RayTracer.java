package engine;

import math.Intersection;
import math.Ray;

import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public class RayTracer {
    private static final float REFLECT_BIAS = 1e-3f;

    public record PixelHit(int x, int y) {}

    private final ViewportState state;
    private final RandomGenerator rng;

    public RayTracer(ViewportState state, long seed) {
        this.state = state;
        this.rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    public RayTracer(ViewportState state, RandomGenerator rng) {
        this.state = state;
        this.rng = rng;
    }

    /** Trace one batch into a fresh buffer. Primarily for tests. */
    public float[][] trace() {
        var buf = new float[state.sensorPixelsH()][state.sensorPixelsW()];
        traceInto(buf);
        return buf;
    }

    /**
     * Trace one batch and accumulate hits into {@code buf}. Used per-frame for progressive
     * refinement.
     */
    public void traceInto(float[][] buf) {
        for (var light : state.lights()) {
            for (var ray : light.sample(state.samplesPerLight(), rng)) {
                traceFrom(ray).ifPresent(p -> buf[p.y()][p.x()] += 1f);
            }
        }
    }

    public Optional<PixelHit> traceFrom(Ray ray) {
        return traceFrom(ray, 0);
    }

    private Optional<PixelHit> traceFrom(Ray ray, int depth) {
        if (depth > state.maxBounces()) {
            return Optional.empty();
        }
        var camera = state.cameraSensor();
        var cameraHit = camera.intersect(ray);

        Intersection nearestObj = null;
        for (var obj : state.objects()) {
            var hit = obj.intersect(ray);
            if (hit.isPresent()
                    && (nearestObj == null || hit.get().distance() < nearestObj.distance())) {
                nearestObj = hit.get();
            }
        }

        if (cameraHit.isPresent()
                && (nearestObj == null || cameraHit.get().distance() < nearestObj.distance())) {
            // Camera is the nearest hit. Front-side → record; back-side → discard. Either way,
            // stop.
            if (camera.isFrontHit(ray)) {
                return Optional.of(toPixel(cameraHit.get().point()));
            }
            return Optional.empty();
        }

        if (nearestObj != null) {
            var refDir = nearestObj.reflectDirection();
            var biasedOrigin = nearestObj.point().add(refDir.scale(REFLECT_BIAS));
            return traceFrom(new Ray(biasedOrigin, refDir), depth + 1);
        }
        return Optional.empty();
    }

    private PixelHit toPixel(math.Vec3 hit) {
        var c = state.cameraSensor();
        float u = c.u(hit);
        float v = c.v(hit);
        int px = clamp((int) (u * state.sensorPixelsW()), 0, state.sensorPixelsW() - 1);
        int py = clamp((int) (v * state.sensorPixelsH()), 0, state.sensorPixelsH() - 1);
        return new PixelHit(px, py);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
