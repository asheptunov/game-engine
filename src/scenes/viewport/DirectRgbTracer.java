package scenes.viewport;

import math.Ray;
import math.Vec3;
import profiling.RuntimeMetrics;
import profiling.TraceProfile;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.SceneObject;
import java.util.List;

/** Iterative diffuse/mirror/dielectric paths and progressive RGB means; no per-ray objects. */
public final class DirectRgbTracer {
    private static final float BIAS = 1e-3f;
    private final ViewportState state;
    private List<SceneInstance> cachedInstances = List.of();
    private List<SceneObject> cachedLegacyObjects = List.of();
    private PreparedObject[] objects = new PreparedObject[0];
    private float[][][] buffer;
    private double[][][] mean;
    private AccumulationKey accumulationKey;
    private long samples;
    private boolean continuation;

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

    /** Returns reusable averaged radiance; caller holds the state monitor for coherent edits. */
    public float[][][] trace() {
        long cpu = RuntimeMetrics.threadCpu(), bytes = RuntimeMetrics.allocatedBytes(), start = System.nanoTime();
        prepare();
        primaryRays = primaryHits = shadowRays = shadowsOccluded = litPixels = 0;
        primaryTests = continuationTests = shadowTests = continuationRays = 0;
        dielectricReflections = dielectricTransmissions = absorptionSegments = 0;
        continuation = false;
        var lights = state.lights().stream().filter(PointLight.class::isInstance)
                .map(PointLight.class::cast).toArray(PointLight[]::new);
        var eye = state.eye();
        var sensor = state.cameraSensor();
        var hit = new Hit();
        var rgb = new float[3];
        var lighting = new float[3];
        var sampler = new Sampler();
        var scattering = new Material.Sample();
        var initialMedia = mediaAt(eye.x(), eye.y(), eye.z());
        var media = new PreparedObject[objects.length + 1];
        int width = state.sensorPixelsW(), height = state.sensorPixelsH();
        var key = new AccumulationKey(cachedInstances, cachedLegacyObjects, List.copyOf(state.lights()),
                eye, sensor, width, height, state.pathDepth(), state.seed(), state.restartVersion());
        if (!key.equals(accumulationKey)) {
            accumulationKey = key;
            samples = 0;
            state.accumulatedSamples(0);
            if (mean == null || buffer[0].length != height || buffer[0][0].length != width) {
                mean = new double[3][height][width];
                buffer = new float[3][height][width];
            } else {
                for (int c = 0; c < 3; c++) for (int y = 0; y < height; y++) {
                    java.util.Arrays.fill(mean[c][y], 0);
                    java.util.Arrays.fill(buffer[c][y], 0);
                }
            }
        }
        int batch = state.paused() ? 0 : (int) Math.min(state.samplesPerFrame(), Math.max(0, state.effectiveTarget() - samples));
        int rendered = 0;
        for (int s = 0; s < batch; s++) {
            double inverseCount = 1.0 / (samples + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (state.pathDepth() > 0) sampler.reset(state.seed(), (long)y*width + x, samples);
                    // Preserve the phase 1 pixel-center diagnostic exactly at depth zero.
                    float u = (x + (state.pathDepth() == 0 ? .5f : sampler.next())) / width;
                    float v = (y + (state.pathDepth() == 0 ? .5f : sampler.next())) / height;
                    float dx = sensor.origin().x() + sensor.edge1().x()*u + sensor.edge2().x()*v - eye.x();
                    float dy = sensor.origin().y() + sensor.edge1().y()*u + sensor.edge2().y()*v - eye.y();
                    float dz = sensor.origin().z() + sensor.edge1().z()*u + sensor.edge2().z()*v - eye.z();
                    float inverseLength = 1 / (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    dx *= inverseLength; dy *= inverseLength; dz *= inverseLength;
                    primaryRays++;
                    path(eye.x(), eye.y(), eye.z(), dx, dy, dz, hit, lights, rgb, lighting, sampler, scattering, initialMedia, media);
                    for (int c = 0; c < 3; c++) {
                        mean[c][y][x] += (rgb[c] - mean[c][y][x]) * inverseCount;
                        buffer[c][y][x] = (float) mean[c][y][x];
                    }
                    if (rgb[0] + rgb[1] + rgb[2] > 0) litPixels++;
                }
            }
            samples++;
            rendered++;
            // Release the monitor between batches; finish at least one complete sensor sample.
            if (System.nanoTime() - start >= 50_000_000L) break;
        }
        state.accumulatedSamples(samples);
        traceNanos = System.nanoTime() - start;
        profile = new TraceProfile.Stats(RuntimeMetrics.delta(cpu, RuntimeMetrics.threadCpu()),
                RuntimeMetrics.delta(bytes, RuntimeMetrics.allocatedBytes()), primaryTests, shadowTests,
                state.preset(), state.pathDepth(), rendered, continuationRays, continuationTests,
                samples, state.samplingStatus(), state.seed(), dielectricReflections, dielectricTransmissions, absorptionSegments);
        return buffer;
    }

    private void path(float ox, float oy, float oz, float dx, float dy, float dz, Hit hit,
                      PointLight[] lights, float[] rgb, float[] lighting, Sampler sampler, Material.Sample scattering,
                      PreparedObject[] initialMedia, PreparedObject[] media) {
        rgb[0] = rgb[1] = rgb[2] = 0;
        float red = 1, green = 1, blue = 1;
        int mediumCount = initialMedia.length;
        System.arraycopy(initialMedia, 0, media, 0, mediumCount);
        for (int depth = 0; depth <= state.pathDepth(); depth++) {
            continuation = depth != 0;
            if (!nearestHit(ox, oy, oz, dx, dy, dz, hit)) break; // Black environment.
            if (depth == 0) primaryHits++;
            Material medium = mediumCount == 0 ? null : media[mediumCount-1].primitives[0].material;
            if (medium != null) {
                absorptionSegments++;
                red *= (float)Math.exp(-medium.absorption().x()*hit.distance);
                green *= (float)Math.exp(-medium.absorption().y()*hit.distance);
                blue *= (float)Math.exp(-medium.absorption().z()*hit.distance);
            }
            var material = hit.primitive.material;
            if (material.kind() == Material.Kind.DIFFUSE) {
                light(hit, lights, lighting, medium);
                rgb[0] += red*lighting[0]; rgb[1] += green*lighting[1]; rgb[2] += blue*lighting[2];
            }
            // Evaluate direct lighting at the final vertex before stopping continuation.
            if (depth == state.pathDepth()) break;
            if (red + green + blue == 0) break;
            if (material.kind() != Material.Kind.DIELECTRIC &&
                    material.color().x()*red + material.color().y()*green + material.color().z()*blue == 0) break;
            float sign = hit.frontFace ? 1 : -1;
            float nx = hit.nx*sign, ny = hit.ny*sign, nz = hit.nz*sign;
            if (material.kind() == Material.Kind.DIELECTRIC) {
                float incident = medium == null ? 1 : medium.ior();
                float exit = hit.frontFace ? material.ior() : mediumCount < 2 ? 1 : media[mediumCount-2].primitives[0].material.ior();
                material.sampleDielectric(dx,dy,dz,nx,ny,nz,incident,exit,sampler.next(),scattering);
                if (scattering.transmitted) {
                    dielectricTransmissions++;
                    if (hit.frontFace) media[mediumCount++] = hit.object;
                    else if (mediumCount > 0) mediumCount--;
                } else dielectricReflections++;
            } else material.sample(dx, dy, dz, nx, ny, nz, sampler.next(), sampler.next(), scattering);
            red *= scattering.red; green *= scattering.green; blue *= scattering.blue;
            dx = scattering.dx; dy = scattering.dy; dz = scattering.dz;
            float offset = scattering.transmitted ? -BIAS : BIAS;
            ox = hit.x + nx*offset; oy = hit.y + ny*offset; oz = hit.z + nz*offset;
            continuationRays++;
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
            if (occluded(hit.x + nx*BIAS, hit.y + ny*BIAS, hit.z + nz*BIAS,
                    lx, ly, lz, distance - BIAS, hit.primitive)) {
                shadowsOccluded++;
                continue;
            }
            // Intensity is radiant intensity per steradian. Lambertian BRDF is reflectance/pi.
            float weight = cosine * light.intensity() / ((float) Math.PI * distanceSquared);
            var color = hit.primitive.material.color();
            float ar=1, ag=1, ab=1;
            if (medium != null) {
                // Visibility blocks all boundaries, so an unblocked segment stays in this medium.
                ar=(float)Math.exp(-medium.absorption().x()*distance);
                ag=(float)Math.exp(-medium.absorption().y()*distance);
                ab=(float)Math.exp(-medium.absorption().z()*distance);
            }
            red += weight * color.x() * light.color().x() * ar;
            green += weight * color.y() * light.color().y() * ag;
            blue += weight * color.z() * light.color().z() * ab;
        }
        rgb[0] = red; rgb[1] = green; rgb[2] = blue;
    }

    boolean nearestHit(float ox, float oy, float oz, float dx, float dy, float dz, Hit hit) {
        float distance = Float.POSITIVE_INFINITY;
        PreparedPrimitive nearest = null;
        PreparedObject nearestObject = null;
        for (var object : objects) {
            if (object.primitives.length > 1 && !object.overlaps(ox, oy, oz, dx, dy, dz, distance)) continue;
            for (var primitive : object.primitives) {
                if (continuation) continuationTests++; else primaryTests++;
                float t = primitive.distance(ox, oy, oz, dx, dy, dz);
                if (t < distance) { distance = t; nearest = primitive; nearestObject = object; }
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

    /** Test adapter, outside the hot path. */
    float[] radiance(Ray ray, long sample) {
        prepare();
        var rgb = new float[3];
        var sampler = new Sampler(); sampler.reset(state.seed(), 0, sample);
        var origin = ray.origin(); var direction = ray.direction().normalized();
        var lights = state.lights().stream().filter(PointLight.class::isInstance).map(PointLight.class::cast).toArray(PointLight[]::new);
        path(origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(),
                new Hit(), lights, rgb, new float[3], sampler, new Material.Sample(),
                mediaAt(origin.x(),origin.y(),origin.z()), new PreparedObject[objects.length+1]);
        return rgb;
    }

    /** Closed, nonintersecting boundaries: the nearest forward crossing faces out iff inside.
     * Sort containing solids by exit distance, outermost first. Done once per camera batch. */
    private PreparedObject[] mediaAt(float x, float y, float z) {
        var media = new PreparedObject[objects.length];
        var distances = new float[objects.length];
        var hit = new Hit(); int count=0;
        for (var object : objects) {
            if (object.primitives.length == 0 || object.primitives[0].material.kind() != Material.Kind.DIELECTRIC) continue;
            float distance=Float.POSITIVE_INFINITY; PreparedPrimitive nearest=null;
            for (var primitive : object.primitives) {
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
        return java.util.Arrays.copyOf(media,count);
    }

    /** Test adapter, outside the hot path. */
    Hit intersect(Ray ray) {
        prepare();
        continuation = false;
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
