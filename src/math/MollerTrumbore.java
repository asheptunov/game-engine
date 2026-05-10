package math;

import java.util.Optional;

/**
 * Shared ray-vs-parallelogram parameterization. Tri and Rect both build on this — they only differ
 * in how (u, v) bounds are interpreted (triangle: u >= 0 && v >= 0 && u + v <= 1; rect: each in [0, 1]).
 */
public final class MollerTrumbore {
    private MollerTrumbore() {}

    public record Result(float t, float u, float v) {}

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
