package v1.model.scene;

import java.util.Objects;

public class Vector {
    public static final Vector X_HAT = new Vector(1, 0, 0);
    public static final Vector Y_HAT = new Vector(0, 1, 0);
    public static final Vector Z_HAT = new Vector(0, 0, 1);

    private final double x, y, z;
    private double mag = -1;
    private Vector hat;

    public Vector(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }
    public double y()
    {
        return y;
    }
    public double z() {
        return z;
    }

    public double dot(Vector other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vector cross(Vector other) {
        return X_HAT.times(y * other.z - z * other.y)
                .minus(Y_HAT.times(x * other.z - z * other.x))
                .plus(Z_HAT.times(x * other.y - y * other.x));
    }

    public Vector minus(Vector other) {
        return new Vector(x - other.x, y - other.y, z - other.z);
    }

    public Vector plus(Vector other) {
        return new Vector(x + other.x, y + other.y, z + other.z);
    }

    public Vector times(double amount) {
        return new Vector(x * amount, y * amount, z * amount);
    }

    public Vector times(Vector other) {
        return new Vector(x * other.x, y * other.y, z * other.z);
    }

    public Vector div(double amount) {
        return new Vector(x / amount, y / amount, z / amount);
    }

    public Vector div(Vector other) {
        return new Vector(x / other.x, y / other.y, z / other.z);
    }

    public double magnitude() {
        if (mag == -1) {
            mag = Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2) + Math.pow(z, 2));
        }
        return mag;
    }

    public Vector hat() {
        if (hat == null) {
            hat = this.div(magnitude());
        }
        return hat;
    }

    @Override
    public String toString() {
        return "Vector{" +
                "x=" + x +
                ", y=" + y +
                ", z=" + z +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vector vector = (Vector) o;
        return Double.compare(x, vector.x) == 0
                && Double.compare(y, vector.y) == 0
                && Double.compare(z, vector.z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }
}
