package scenes.viewport;

import math.Ray;
import math.Vec3;
import profiling.RuntimeMetrics;
import profiling.TraceProfile;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.SceneObject;
import java.util.List;

/** Deterministic, zero-continuation Lambertian transport. No transient objects per sensor ray. */
public final class DirectRgbTracer {
    private static final float BIAS = 1e-3f;
    private final ViewportState state;
    private List<SceneInstance> cachedInstances = List.of();
    private List<SceneObject> cachedLegacyObjects = List.of();
    private PreparedObject[] objects = new PreparedObject[0];
    private float[][][] buffer;

    public int primaryRays, primaryHits, shadowRays, shadowsOccluded, litPixels;
    public long traceNanos, primaryTests, shadowTests;
    public TraceProfile.Stats profile;

    /** Retain the geometric normal and orientation; derive the shading normal separately. */
    static final class Hit {
        PreparedPrimitive primitive;
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
        var prepared = new java.util.ArrayList<PreparedObject>();
        for (var instance : instances) prepared.add(new PreparedObject(instance));
        for (int i = 0; i < legacy.size(); i++) {
            var instance = new SceneInstance("primitive-" + i, List.of(legacy.get(i)), Transform.IDENTITY,
                    new Material("white", new Vec3(1, 1, 1)));
            prepared.add(new PreparedObject(instance));
        }
        objects = prepared.toArray(PreparedObject[]::new);
        cachedInstances = instances;
        cachedLegacyObjects = legacy;
    }

    /** Returns reusable planar linear RGB storage, valid until the next trace. */
    public float[][][] trace() {
        long cpu = RuntimeMetrics.threadCpu(), bytes = RuntimeMetrics.allocatedBytes(), start = System.nanoTime();
        prepare();
        primaryRays = primaryHits = shadowRays = shadowsOccluded = litPixels = 0;
        primaryTests = shadowTests = 0;
        var lights = state.lights().stream().filter(PointLight.class::isInstance)
                .map(PointLight.class::cast).toArray(PointLight[]::new);
        var eye = state.eye();
        var sensor = state.cameraSensor();
        var hit = new Hit();
        var rgb = new float[3];
        int width = state.sensorPixelsW(), height = state.sensorPixelsH();
        if (buffer[0].length != height || buffer[0][0].length != width)
            buffer = new float[3][height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float u = (x + .5f) / width, v = (y + .5f) / height;
                float dx = sensor.origin().x() + sensor.edge1().x()*u + sensor.edge2().x()*v - eye.x();
                float dy = sensor.origin().y() + sensor.edge1().y()*u + sensor.edge2().y()*v - eye.y();
                float dz = sensor.origin().z() + sensor.edge1().z()*u + sensor.edge2().z()*v - eye.z();
                float inverseLength = 1 / (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                dx *= inverseLength; dy *= inverseLength; dz *= inverseLength;
                primaryRays++;
                rgb[0] = rgb[1] = rgb[2] = 0;
                if (nearestHit(eye.x(), eye.y(), eye.z(), dx, dy, dz, hit)) {
                    primaryHits++;
                    light(hit, lights, rgb);
                }
                buffer[0][y][x] = rgb[0]; buffer[1][y][x] = rgb[1]; buffer[2][y][x] = rgb[2];
                if (rgb[0] + rgb[1] + rgb[2] > 0) litPixels++;
            }
        }
        traceNanos = System.nanoTime() - start;
        profile = new TraceProfile.Stats(RuntimeMetrics.delta(cpu, RuntimeMetrics.threadCpu()),
                RuntimeMetrics.delta(bytes, RuntimeMetrics.allocatedBytes()), primaryTests, shadowTests,
                state.preset(), 0, 1, 0);
        return buffer;
    }

    private void light(Hit hit, PointLight[] lights, float[] rgb) {
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
            if (occluded(hit.x + nx*BIAS, hit.y + ny*BIAS, hit.z + nz*BIAS,
                    lx, ly, lz, distance - BIAS, hit.primitive)) {
                shadowsOccluded++;
                continue;
            }
            // Intensity is radiant intensity per steradian. Lambertian BRDF is reflectance/pi.
            float weight = cosine * light.intensity() / ((float) Math.PI * distanceSquared);
            var color = hit.primitive.material.color();
            red += weight * color.x() * light.color().x();
            green += weight * color.y() * light.color().y();
            blue += weight * color.z() * light.color().z();
        }
        rgb[0] = red; rgb[1] = green; rgb[2] = blue;
    }

    boolean nearestHit(float ox, float oy, float oz, float dx, float dy, float dz, Hit hit) {
        float distance = Float.POSITIVE_INFINITY;
        PreparedPrimitive nearest = null;
        for (var object : objects) {
            if (object.primitives.length > 1 && !object.overlaps(ox, oy, oz, dx, dy, dz, distance)) continue;
            for (var primitive : object.primitives) {
                primaryTests++;
                float t = primitive.distance(ox, oy, oz, dx, dy, dz);
                if (t < distance) { distance = t; nearest = primitive; }
            }
        }
        if (nearest == null) return false;
        hit.primitive = nearest;
        hit.distance = distance;
        hit.x = ox + dx*distance; hit.y = oy + dy*distance; hit.z = oz + dz*distance;
        nearest.normal(hit.x, hit.y, hit.z, hit);
        hit.frontFace = hit.nx*dx + hit.ny*dy + hit.nz*dz < 0;
        return true;
    }

    /** Test adapter, outside the hot path. */
    Hit intersect(Ray ray) {
        prepare();
        var hit = new Hit(); var origin = ray.origin(); var direction = ray.direction();
        return nearestHit(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), hit)
                ? hit : null;
    }

    private boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz,
                             float maxDistance, PreparedPrimitive source) {
        for (var object : objects) {
            if (object.primitives.length > 1 && !object.overlaps(ox, oy, oz, dx, dy, dz, maxDistance)) continue;
            for (var primitive : object.primitives) {
                // Only flat source primitives can be skipped. A sphere can occlude its own interior rays.
                if (primitive == source && primitive.flat != null) continue;
                shadowTests++;
                if (primitive.distance(ox, oy, oz, dx, dy, dz) < maxDistance) return true;
            }
        }
        return false;
    }
}
