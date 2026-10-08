package engine;

/** Cached per-instance geometry, conservative world bounds and optional primitive hierarchy. */
final class PreparedObject {
    final PreparedGeometry geometry;
    final PreparedPrimitive[] primitives;
    final PrimitiveBvh bvh;
    private final PrimitiveBvh.Bounds bounds;

    PreparedObject(SceneInstance instance) {
        geometry = PreparedGeometry.prepare(instance.geometry());
        bvh = geometry.primitives().size() >= 16 ? new PrimitiveBvh(instance, geometry) : null;
        bounds = bvh == null ? new PrimitiveBvh.Bounds() : bvh.nodes[0].bounds;
        primitives = new PreparedPrimitive[geometry.primitives().size()];
        for (int i = 0; i < primitives.length; i++) {
            primitives[i] = new PreparedPrimitive(instance, geometry, i);
            if (bvh == null) bounds.include(PrimitiveBvh.bounds(instance, geometry, i));
        }
    }

    boolean overlaps(
            float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance) {
        return bounds.overlaps(ox, oy, oz, dx, dy, dz, maxDistance);
    }

    boolean containsBounds(float x, float y, float z) {
        return x >= bounds.minX
                && x <= bounds.maxX
                && y >= bounds.minY
                && y <= bounds.maxY
                && z >= bounds.minZ
                && z <= bounds.maxZ;
    }
}
