package math;

public record Ray(Vec3 origin, Vec3 direction) {
    public Vec3 at(float t) {
        return origin.add(direction.scale(t));
    }
}
