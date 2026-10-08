package math;

public record Vec3(float x, float y, float z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public float lengthSq() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSq());
    }

    public Vec3 normalized() {
        float len = length();
        if (len == 0f) {
            throw new ArithmeticException("Cannot normalize zero vector");
        }
        return scale(1f / len);
    }
}
