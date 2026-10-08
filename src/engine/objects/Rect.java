package engine.objects;

import math.Intersection;
import math.MollerTrumbore;
import math.Ray;
import math.Vec3;

import java.util.Optional;

/**
 * Parallelogram with corner at {@code origin}, spanning {@code edge1} and {@code edge2}. Points on
 * the rect: {@code origin + s*edge1 + t*edge2} for {@code s, t ∈ [0, 1]}. Natural normal: {@code
 * edge1 × edge2} normalized — the "sensing side" for camera use.
 */
public record Rect(Vec3 origin, Vec3 edge1, Vec3 edge2) implements RenderPrimitive {
    public Vec3 normal() {
        return edge1.cross(edge2).normalized();
    }

    @Override
    public Optional<Intersection> intersect(Ray ray) {
        return MollerTrumbore.compute(ray, origin, edge1, edge2)
                .filter(r -> r.u() >= 0 && r.u() <= 1 && r.v() >= 0 && r.v() <= 1)
                .map(r -> new Intersection(ray.at(r.t()), normal(), r.t(), ray));
    }

    /** True iff the ray approaches the sensing side (normal-facing side) of this rect. */
    public boolean isFrontHit(Ray ray) {
        return ray.direction().dot(normal()) < 0;
    }

    /**
     * Parameter along {@code edge1} for a point on the rect's plane. {@code [0, 1]} when on the
     * rect.
     */
    public float u(Vec3 point) {
        return point.sub(origin).dot(edge1) / edge1.lengthSq();
    }

    /**
     * Parameter along {@code edge2} for a point on the rect's plane. {@code [0, 1]} when on the
     * rect.
     */
    public float v(Vec3 point) {
        return point.sub(origin).dot(edge2) / edge2.lengthSq();
    }
}
