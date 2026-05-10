package scenes.viewport;

import math.Intersection;
import math.Ray;
import scenes.viewport.lights.PointLight;

/**
 * Pinhole-camera backward ray tracer. For each sensor pixel, shoots a primary ray from the eye through
 * the pixel into the scene. On the nearest surface hit, performs direct-lighting (shadow ray + Lambertian
 * cosine term) for every {@link PointLight}. Produces a complete image in one pass — no accumulation needed.
 *
 * <p>Surface normals are flipped to face the camera ray so that Lambertian shading works regardless of
 * the object's geometric vertex order.
 */
public class BackwardRayTracer {
    private static final float SHADOW_BIAS = 1e-3f;

    private final ViewportState state;

    public BackwardRayTracer(ViewportState state) {
        this.state = state;
    }

    public float[][] trace() {
        var sensor = state.cameraSensor();
        var eye = state.eye();
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
                buf[py][px] = shade(new Ray(eye, dir));
            }
        }
        return buf;
    }

    /** Returns the accumulated light contribution at the first surface hit by {@code ray}. */
    public float shade(Ray ray) {
        var hit = nearestHit(ray);
        if (hit == null) {
            return 0;
        }
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
            if (occluded(new Ray(shadowOrigin, lightDir), lightDist - SHADOW_BIAS)) {
                continue;
            }
            float cosTheta = Math.max(0, n.dot(lightDir));
            lit += cosTheta;
        }
        return lit;
    }

    private Intersection nearestHit(Ray ray) {
        Intersection nearest = null;
        for (var obj : state.objects()) {
            var h = obj.intersect(ray);
            if (h.isPresent() && (nearest == null || h.get().distance() < nearest.distance())) {
                nearest = h.get();
            }
        }
        return nearest;
    }

    private boolean occluded(Ray ray, float maxDist) {
        for (var obj : state.objects()) {
            var h = obj.intersect(ray);
            if (h.isPresent() && h.get().distance() < maxDist) {
                return true;
            }
        }
        return false;
    }
}
