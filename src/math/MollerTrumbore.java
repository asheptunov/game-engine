package math;

import java.util.Optional;

/**
 * Shared ray-vs-parallelogram parameterization. Tri and Rect both build on this — they only differ
 * in how (u, v) bounds are interpreted (triangle: u >= 0 && v >= 0 && u + v <= 1; rect: each in [0,
 * 1]).
 */
public final class MollerTrumbore {
    private MollerTrumbore() {}

    public record Result(float t, float u, float v) {}

    /** Bounded surface intersection without allocating a result for each candidate. */
    public static float distance(Ray ray, Vec3 origin, Vec3 edge1, Vec3 edge2, boolean triangle) {
        return distance(
                ray.origin().x(),
                ray.origin().y(),
                ray.origin().z(),
                ray.direction().x(),
                ray.direction().y(),
                ray.direction().z(),
                origin,
                edge1,
                edge2,
                triangle);
    }

    /**
     * Scalar hot path; preserves the vector implementation's operation order without temporary
     * vectors.
     */
    public static float distance(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            Vec3 origin,
            Vec3 edge1,
            Vec3 edge2,
            boolean triangle) {
        float hx = dy * edge2.z() - dz * edge2.y();
        float hy = dz * edge2.x() - dx * edge2.z();
        float hz = dx * edge2.y() - dy * edge2.x();
        float det = edge1.x() * hx + edge1.y() * hy + edge1.z() * hz;
        if (Math.abs(det) < Plane.EPSILON) return Float.POSITIVE_INFINITY;
        float invDet = 1f / det;
        float sx = ox - origin.x(), sy = oy - origin.y(), sz = oz - origin.z();
        float u = invDet * (sx * hx + sy * hy + sz * hz);
        if (u < 0 || u > 1) return Float.POSITIVE_INFINITY;
        float qx = sy * edge1.z() - sz * edge1.y();
        float qy = sz * edge1.x() - sx * edge1.z();
        float qz = sx * edge1.y() - sy * edge1.x();
        float v = invDet * (dx * qx + dy * qy + dz * qz);
        if (v < 0 || (triangle ? u + v > 1 : v > 1)) return Float.POSITIVE_INFINITY;
        float t = invDet * (edge2.x() * qx + edge2.y() * qy + edge2.z() * qz);
        return t > Plane.EPSILON ? t : Float.POSITIVE_INFINITY;
    }

    public static Optional<Result> compute(Ray ray, Vec3 origin, Vec3 edge1, Vec3 edge2) {
        var h = ray.direction().cross(edge2);
        float det = edge1.dot(h);
        if (Math.abs(det) < Plane.EPSILON) {
            return Optional.empty();
        }
        float invDet = 1f / det;
        var s = ray.origin().sub(origin);
        float u = invDet * s.dot(h);
        var q = s.cross(edge1);
        float v = invDet * ray.direction().dot(q);
        float t = invDet * edge2.dot(q);
        if (t <= Plane.EPSILON) {
            return Optional.empty();
        }
        return Optional.of(new Result(t, u, v));
    }
}
