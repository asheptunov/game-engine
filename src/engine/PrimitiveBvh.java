package engine;

import engine.objects.*;

import math.Vec3;

import java.util.*;

/**
 * Median-split, largest-axis BVH. Child indices support near-first traversal; preorder escape links
 * retain the stackless reference.
 */
final class PrimitiveBvh {
    static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        void include(Vec3 p) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }

        void include(Bounds b) {
            minX = Math.min(minX, b.minX);
            minY = Math.min(minY, b.minY);
            minZ = Math.min(minZ, b.minZ);
            maxX = Math.max(maxX, b.maxX);
            maxY = Math.max(maxY, b.maxY);
            maxZ = Math.max(maxZ, b.maxZ);
        }

        float center(int axis) {
            return switch (axis) {
                case 0 -> minX / 2 + maxX / 2;
                case 1 -> minY / 2 + maxY / 2;
                default -> minZ / 2 + maxZ / 2;
            };
        }

        boolean overlaps(
                float ox, float oy, float oz, float dx, float dy, float dz, float distance) {
            float near = 0, far = distance;
            if (dx == 0) {
                if (ox < minX || ox > maxX) return false;
            } else {
                float a = (minX - ox) / dx, b = (maxX - ox) / dx;
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
            if (dy == 0) {
                if (oy < minY || oy > maxY) return false;
            } else {
                float a = (minY - oy) / dy, b = (maxY - oy) / dy;
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
            if (dz == 0) {
                if (oz < minZ || oz > maxZ) return false;
            } else {
                float a = (minZ - oz) / dz, b = (maxZ - oz) / dz;
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
            }
            return near <= far;
        }

        /** Forward slab entry in ray units; infinity means no overlap within the limit. */
        float entryDistance(
                float ox, float oy, float oz, float dx, float dy, float dz, float distance) {
            float near =
                    Math.max(
                            0,
                            Math.max(
                                    axisEntry(ox, dx, minX, maxX),
                                    Math.max(
                                            axisEntry(oy, dy, minY, maxY),
                                            axisEntry(oz, dz, minZ, maxZ))));
            float far =
                    Math.min(
                            distance,
                            Math.min(
                                    axisExit(ox, dx, minX, maxX),
                                    Math.min(
                                            axisExit(oy, dy, minY, maxY),
                                            axisExit(oz, dz, minZ, maxZ))));
            return near <= far && near != Float.POSITIVE_INFINITY ? near : Float.POSITIVE_INFINITY;
        }

        private static float axisEntry(float origin, float direction, float min, float max) {
            if (direction == 0) {
                return origin >= min && origin <= max ? 0 : Float.POSITIVE_INFINITY;
            }
            return Math.min((min - origin) / direction, (max - origin) / direction);
        }

        private static float axisExit(float origin, float direction, float min, float max) {
            if (direction == 0) {
                return Float.POSITIVE_INFINITY;
            }
            return Math.max((min - origin) / direction, (max - origin) / direction);
        }
    }

    static final class Node {
        final Bounds bounds;
        final int from, to;
        int escape;
        int right;

        Node(Bounds b, int from, int to) {
            this.bounds = b;
            this.from = from;
            this.to = to;
        }

        boolean leaf() {
            return to >= 0;
        }
    }

    final Node[] nodes;
    final int[] order;

    PrimitiveBvh(SceneInstance instance, PreparedGeometry geometry) {
        this(primitiveBounds(instance, geometry));
    }

    /** Same immutable hierarchy layout for world-space object bounds. */
    PrimitiveBvh(Bounds[] bounds) {
        int n = bounds.length;
        if (n == 0) {
            throw new IllegalArgumentException("Hierarchy needs at least one bound");
        }
        var ids = new Integer[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
        }
        var list = new ArrayList<Node>();
        build(list, ids, bounds, 0, n);
        nodes = list.toArray(Node[]::new);
        order = Arrays.stream(ids).mapToInt(Integer::intValue).toArray();
    }

    private static Bounds[] primitiveBounds(SceneInstance instance, PreparedGeometry geometry) {
        var bounds = new Bounds[geometry.primitives().size()];
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = bounds(instance, geometry, i);
        }
        return bounds;
    }

    private static void build(List<Node> nodes, Integer[] ids, Bounds[] bounds, int from, int to) {
        var b = new Bounds();
        for (int i = from; i < to; i++) b.include(bounds[ids[i]]);
        var node = new Node(b, from, to - from <= 4 ? to : -1);
        nodes.add(node);
        if (!node.leaf()) {
            float x = b.maxX - b.minX, y = b.maxY - b.minY, z = b.maxZ - b.minZ;
            int axis = x >= y && x >= z ? 0 : y >= z ? 1 : 2;
            Arrays.sort(ids, from, to, Comparator.comparingDouble(i -> bounds[i].center(axis)));
            int mid = (from + to) / 2;
            build(nodes, ids, bounds, from, mid);
            node.right = nodes.size();
            build(nodes, ids, bounds, mid, to);
        }
        node.escape = nodes.size();
    }

    static Bounds bounds(SceneInstance instance, PreparedGeometry geometry, int index) {
        var b = new Bounds();
        var t = instance.transform();
        switch (geometry.primitives().get(index)) {
            case Tri tri -> {
                b.include(t.point(tri.a()));
                b.include(t.point(tri.b()));
                b.include(t.point(tri.c()));
            }
            case Rect r -> {
                b.include(t.point(r.origin()));
                b.include(t.point(r.origin().add(r.edge1())));
                b.include(t.point(r.origin().add(r.edge2())));
                b.include(t.point(r.origin().add(r.edge1()).add(r.edge2())));
            }
            case Sphere s -> {
                for (int x = -1; x <= 1; x += 2)
                    for (int y = -1; y <= 1; y += 2)
                        for (int z = -1; z <= 1; z += 2)
                            b.include(
                                    t.point(
                                            s.center()
                                                    .add(
                                                            new Vec3(
                                                                    x * s.radius(),
                                                                    y * s.radius(),
                                                                    z * s.radius()))));
            }
        }
        float pad = 1e-4f;
        b.minX = Math.nextDown(b.minX - pad);
        b.minY = Math.nextDown(b.minY - pad);
        b.minZ = Math.nextDown(b.minZ - pad);
        b.maxX = Math.nextUp(b.maxX + pad);
        b.maxY = Math.nextUp(b.maxY + pad);
        b.maxZ = Math.nextUp(b.maxZ + pad);
        return b;
    }
}
