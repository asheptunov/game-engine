package engine;

import engine.objects.Tri;

import math.Vec3;

import java.util.*;

/** Prepared, immutable nearest-hit service independent of render sessions and viewport state. */
public final class SpatialQuery {
    private record Entry(SceneSnapshot.RenderEntry source, PreparedObject prepared) {}

    private final SceneSnapshot snapshot;
    private final List<Entry> entries;
    private final boolean acceleration;

    private SpatialQuery(SceneSnapshot snapshot, boolean acceleration) {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.acceleration = acceleration;
        entries =
                snapshot.renderEntries().stream()
                        .map(e -> new Entry(e, new PreparedObject(e.instance())))
                        .toList();
    }

    public static SpatialQuery prepare(SceneSnapshot snapshot) {
        return new SpatialQuery(snapshot, true);
    }

    /** Reference option used to verify accelerated query results. */
    public static SpatialQuery prepare(SceneSnapshot snapshot, boolean acceleration) {
        return new SpatialQuery(snapshot, acceleration);
    }

    /** Exact reference (zero-aperture) camera ray; u/v are 0..1 and v=0 is the image bottom. */
    public static RayQuery screenRay(Camera camera, float u, float v) {
        Objects.requireNonNull(camera, "camera").validated();
        if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
            throw new IllegalArgumentException("Screen coordinates must be 0..1");
        var sample = new Camera.RaySample();
        camera.compile().reference(u, v, sample);
        return new RayQuery(
                new Vec3(sample.ox, sample.oy, sample.oz),
                new Vec3(sample.dx, sample.dy, sample.dz));
    }

    public Optional<RayHit> pick(Camera camera, float u, float v) {
        return nearest(screenRay(camera, u, v));
    }

    public Optional<RayHit> nearest(Vec3 origin, Vec3 direction) {
        return nearest(new RayQuery(origin, direction));
    }

    public Optional<RayHit> nearest(RayQuery query) {
        Objects.requireNonNull(query, "query");
        var d = query.normalizedDirection();
        var o = query.origin();
        float nearest = query.maximumDistance();
        Entry nearestEntry = null;
        PreparedPrimitive nearestPrimitive = null;
        for (var entry : entries) {
            if (query.excludedNodes().contains(entry.source().node().id())) continue;
            var object = entry.prepared();
            if (acceleration
                    && object.primitives.length > 1
                    && !object.overlaps(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), nearest))
                continue;
            var bvh = acceleration ? object.bvh : null;
            for (int node = 0; node < (bvh == null ? 1 : bvh.nodes.length); ) {
                int from = 0, to = object.primitives.length;
                if (bvh != null) {
                    var n = bvh.nodes[node];
                    if (!n.bounds.overlaps(o.x(), o.y(), o.z(), d.x(), d.y(), d.z(), nearest)) {
                        node = n.escape;
                        continue;
                    }
                    node++;
                    if (!n.leaf()) continue;
                    from = n.from;
                    to = n.to;
                } else node++;
                for (int i = from; i < to; i++) {
                    var primitive = object.primitives[bvh == null ? i : bvh.order[i]];
                    float distance = primitive.distance(o.x(), o.y(), o.z(), d.x(), d.y(), d.z());
                    if (distance < query.minimumDistance()) continue;
                    if (distance < nearest
                            || (distance == nearest
                                    && nearestEntry == entry
                                    && primitive.primitiveId < nearestPrimitive.primitiveId)) {
                        nearest = distance;
                        nearestEntry = entry;
                        nearestPrimitive = primitive;
                    }
                }
            }
        }
        if (nearestEntry == null) return Optional.empty();
        var world = o.add(d.scale(nearest));
        var source = nearestEntry.source();
        var local = source.worldTransform().inversePoint(world);
        var normal = nearestPrimitive.normalAt(world.x(), world.y(), world.z());
        Optional<TriangleCoordinates> coordinates =
                nearestPrimitive.geometry instanceof Tri tri
                        ? Optional.of(barycentric(local, tri))
                        : Optional.empty();
        long face = nearestEntry.prepared().geometry.sourceFaceId(nearestPrimitive.primitiveId);
        return Optional.of(
                new RayHit(
                        snapshot.revision(),
                        source.node().id(),
                        source.geometry().id(),
                        source.geometry().revision(),
                        nearestPrimitive.primitiveId,
                        face,
                        nearest,
                        world,
                        local,
                        normal,
                        normal.dot(d) < 0,
                        coordinates));
    }

    private static TriangleCoordinates barycentric(Vec3 p, Tri t) {
        var v0 = t.b().sub(t.a());
        var v1 = t.c().sub(t.a());
        var v2 = p.sub(t.a());
        double d00 = v0.dot(v0),
                d01 = v0.dot(v1),
                d11 = v1.dot(v1),
                d20 = v2.dot(v0),
                d21 = v2.dot(v1);
        double denominator = d00 * d11 - d01 * d01;
        float b = (float) ((d11 * d20 - d01 * d21) / denominator),
                c = (float) ((d00 * d21 - d01 * d20) / denominator);
        return new TriangleCoordinates(1 - b - c, b, c);
    }
}
