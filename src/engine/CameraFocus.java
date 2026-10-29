package engine;

import math.Vec3;

import java.util.*;

/** Query-owned geometry cache and BVH traversal; never uses transport scratch or RNG. */
public final class CameraFocus {
    public record Scene(
            List<SceneInstance> instances,
            List<engine.objects.RenderPrimitive> objects,
            long revision) {
        public static Scene capture(ViewportState state) {
            return new Scene(
                    List.copyOf(state.instances()),
                    List.copyOf(state.objects()),
                    state.focusSceneRevision());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Scene s)
                    || revision != s.revision
                    || instances.size() != s.instances.size()
                    || objects.size() != s.objects.size()) return false;
            for (int i = 0; i < instances.size(); i++) {
                var a = instances.get(i);
                var b = s.instances.get(i);
                if (!a.name().equals(b.name())
                        || a.geometry() != b.geometry()
                        || a.transform() != b.transform()) return false;
            }
            for (int i = 0; i < objects.size(); i++)
                if (objects.get(i) != s.objects.get(i)) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return instances.size() * 31 + objects.size();
        }
    }

    public record Subject(String name, Object geometry) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Subject s && name.equals(s.name) && geometry == s.geometry;
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + System.identityHashCode(geometry);
        }
    }

    public record Measurement(
            FocusTargetSource source,
            Subject subject,
            Vec3 hit,
            float distance,
            long captured,
            long completed,
            long epoch,
            Scene scene,
            Camera camera,
            String error) {}

    private record Entry(SceneInstance instance, PreparedObject prepared, Subject subject) {}

    private List<Entry> cache = List.of();
    private Map<GeometryData, PreparedObject> geometryCache = new IdentityHashMap<>();
    private final Camera.RaySample localRay = new Camera.RaySample();
    private Scene cachedScene;
    long preparations, queries, primitiveTests, queryNanos;

    public Measurement resolve(
            FocusTargetSource source,
            Camera camera,
            Scene scene,
            long epoch,
            long captured,
            java.util.function.LongSupplier clock) {
        long start = System.nanoTime();
        queries++;
        try {
            if (!(source instanceof FocusTargetSource.Screen screen))
                throw new IllegalArgumentException("No resolver for focus source");
            prepare(scene);
            var ray = new Camera.RaySample();
            var compiled = camera.compile();
            compiled.reference(screen.u(), screen.v(), ray);
            float nearest = Float.POSITIVE_INFINITY;
            Subject subject = null;
            for (var entry : cache) {
                float before = nearest;
                var object = entry.prepared();
                localRay(entry.instance().transform(), ray);
                if (!object.overlaps(
                        localRay.ox,
                        localRay.oy,
                        localRay.oz,
                        localRay.dx,
                        localRay.dy,
                        localRay.dz,
                        nearest)) {
                    continue;
                }
                if (object.bvh == null) {
                    for (var primitive : object.primitives)
                        nearest = distance(primitive, localRay, nearest);
                } else {
                    var bvh = object.bvh;
                    int i = 0;
                    while (i < bvh.nodes.length) {
                        var node = bvh.nodes[i];
                        if (!node.bounds.overlaps(
                                localRay.ox,
                                localRay.oy,
                                localRay.oz,
                                localRay.dx,
                                localRay.dy,
                                localRay.dz,
                                nearest)) {
                            i = node.escape;
                            continue;
                        }
                        if (node.leaf())
                            for (int p = node.from; p < node.to; p++)
                                nearest =
                                        distance(
                                                object.primitives[bvh.order[p]], localRay, nearest);
                        i++;
                    }
                }
                if (nearest < before) subject = entry.subject();
            }
            if (subject == null)
                throw new IllegalArgumentException("Focus query missed; distance unchanged");
            var hit =
                    new Vec3(
                            ray.ox + ray.dx * nearest,
                            ray.oy + ray.dy * nearest,
                            ray.oz + ray.dz * nearest);
            float axial =
                    nearest
                            * (ray.dx * compiled.forward.x()
                                    + ray.dy * compiled.forward.y()
                                    + ray.dz * compiled.forward.z());
            camera.withFocus(axial);
            return new Measurement(
                    source,
                    subject,
                    hit,
                    axial,
                    captured,
                    clock.getAsLong(),
                    epoch,
                    scene,
                    camera,
                    null);
        } catch (IllegalArgumentException e) {
            return new Measurement(
                    source,
                    null,
                    null,
                    0,
                    captured,
                    clock.getAsLong(),
                    epoch,
                    scene,
                    camera,
                    e.getMessage());
        } finally {
            queryNanos += System.nanoTime() - start;
        }
    }

    private float distance(PreparedPrimitive p, Camera.RaySample ray, float nearest) {
        primitiveTests++;
        float d = p.distance(ray.ox, ray.oy, ray.oz, ray.dx, ray.dy, ray.dz);
        return d > 0 && d < nearest ? d : nearest;
    }

    /** Preserve world-ray distance under nonuniform scale: never normalize the local direction. */
    private void localRay(Transform t, Camera.RaySample world) {
        float x = world.ox - t.position.x();
        float y = world.oy - t.position.y();
        float z = world.oz - t.position.z();
        localRay.ox = (t.a * x + t.d * y + t.g * z) / t.scale.x();
        localRay.oy = (t.b * x + t.e * y + t.h * z) / t.scale.y();
        localRay.oz = (t.c * x + t.f * y + t.i * z) / t.scale.z();
        localRay.dx = (t.a * world.dx + t.d * world.dy + t.g * world.dz) / t.scale.x();
        localRay.dy = (t.b * world.dx + t.e * world.dy + t.h * world.dz) / t.scale.y();
        localRay.dz = (t.c * world.dx + t.f * world.dy + t.i * world.dz) / t.scale.z();
    }

    private void prepare(Scene scene) {
        if (scene.equals(cachedScene)) return;
        var instances = new ArrayList<>(scene.instances());
        for (int i = 0; i < scene.objects().size(); i++)
            instances.add(
                    new SceneInstance(
                            "legacy-" + i,
                            PreparedGeometry.canonical(scene.objects().get(i)),
                            Transform.IDENTITY,
                            new Material("focus", new Vec3(1, 1, 1))));
        var nextGeometry = new IdentityHashMap<GeometryData, PreparedObject>();
        var next = new ArrayList<Entry>(instances.size());
        for (var instance : instances) {
            var geometry = instance.geometry();
            var prepared = nextGeometry.get(geometry);
            if (prepared == null) {
                prepared = geometryCache.get(geometry);
            }
            if (prepared == null) {
                prepared =
                        new PreparedObject(
                                new SceneInstance(
                                        "focus",
                                        geometry,
                                        Transform.IDENTITY,
                                        new Material("focus", new Vec3(1, 1, 1))));
                preparations++;
            }
            nextGeometry.put(geometry, prepared);
            next.add(new Entry(instance, prepared, new Subject(instance.name(), geometry)));
        }
        // Keep only this scene's assets; animation updates reuse their object-local BVHs.
        geometryCache = nextGeometry;
        cache = List.copyOf(next);
        cachedScene = scene;
    }

    public static float distance(ViewportState snapshot) {
        var result =
                new CameraFocus()
                        .resolve(
                                FocusTargetSource.CENTER,
                                snapshot.camera(),
                                Scene.capture(snapshot),
                                0,
                                0,
                                System::nanoTime);
        if (result.error() != null) throw new IllegalArgumentException(result.error());
        return result.distance();
    }
}
