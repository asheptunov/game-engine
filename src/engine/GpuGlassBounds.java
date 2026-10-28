package engine;

import math.Vec3;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Conservative disjoint-boundary preflight for the GPU's single active clear medium. */
final class GpuGlassBounds {
    private static final Map<PolygonMesh, Vec3[]> LOCAL = new WeakHashMap<>();

    private GpuGlassBounds() {}

    static void validate(WorldSnapshot world) {
        var bounds = new ArrayList<Vec3[]>();
        for (var part : world.instances()) {
            if (!part.material().clearGlass()) {
                continue;
            }
            var box = worldBounds(part);
            for (var other : bounds) {
                if (box[0].x() <= other[1].x()
                        && box[1].x() >= other[0].x()
                        && box[0].y() <= other[1].y()
                        && box[1].y() >= other[0].y()
                        && box[0].z() <= other[1].z()
                        && box[1].z() >= other[0].z()) {
                    throw new RenderCapabilities.UnsupportedInput(
                            "world.instances[" + part.name() + "]",
                            "GPU clear glass requires disjoint world bounds; nested or overlapping"
                                    + " glass is unsupported");
                }
            }
            bounds.add(box);
        }
    }

    private static Vec3[] worldBounds(SceneInstance part) {
        Vec3[] local;
        synchronized (LOCAL) {
            local =
                    LOCAL.computeIfAbsent(
                            (PolygonMesh) part.geometry(),
                            mesh -> {
                                var box = empty();
                                for (var vertex : mesh.editableVertices()) {
                                    include(box, vertex.position());
                                }
                                return box;
                            });
        }
        var box = empty();
        for (int i = 0; i < 8; i++) {
            include(
                    box,
                    part.transform()
                            .point(
                                    new Vec3(
                                            local[i & 1].x(),
                                            local[(i >> 1) & 1].y(),
                                            local[(i >> 2) & 1].z())));
        }
        return box;
    }

    private static Vec3[] empty() {
        return new Vec3[] {
            new Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            new Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        };
    }

    private static void include(Vec3[] box, Vec3 p) {
        box[0] =
                new Vec3(
                        Math.min(box[0].x(), p.x()),
                        Math.min(box[0].y(), p.y()),
                        Math.min(box[0].z(), p.z()));
        box[1] =
                new Vec3(
                        Math.max(box[1].x(), p.x()),
                        Math.max(box[1].y(), p.y()),
                        Math.max(box[1].z(), p.z()));
    }
}
