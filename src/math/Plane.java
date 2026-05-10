package math;

import java.util.Optional;

public record Plane(Vec3 point, Vec3 normal) {
    public static final float EPSILON = 1e-4f;

    public Optional<Float> intersect(Ray ray) {
        float denom = ray.direction().dot(normal);
        if (Math.abs(denom) < EPSILON) {
            return Optional.empty();
        }
        float t = point.sub(ray.origin()).dot(normal) / denom;
        if (t <= EPSILON) {
            return Optional.empty();
        }
        return Optional.of(t);
    }
}
