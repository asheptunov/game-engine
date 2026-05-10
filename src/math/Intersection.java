package math;

public record Intersection(Vec3 point, Vec3 normal, float distance, Ray incoming) {
    public Vec3 reflectDirection() {
        var d = incoming.direction();
        return d.sub(normal.scale(2f * d.dot(normal)));
    }

    public Ray reflectRay() {
        return new Ray(point, reflectDirection());
    }
}
