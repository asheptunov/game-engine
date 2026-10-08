package engine;

import math.Vec3;

/** Positive scale, then Euler rotations X/Y/Z in degrees, then translation. */
public final class Transform {
    /** Relative tolerance used by hierarchy composition and camera uniform-scale checks. */
    public static final float COMPOSITION_TOLERANCE = 1e-5f;

    public static final Transform IDENTITY = new Transform(Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1));
    public final Vec3 position, rotation, scale;
    // Orthonormal rotation matrix. Inverse transforms preserve the ray parameter: never normalize
    // local rays.
    final float a, b, c, d, e, f, g, h, i;

    public Transform(Vec3 position, Vec3 rotation, Vec3 scale) {
        for (var v : new Vec3[] {position, rotation, scale})
            if (!Float.isFinite(v.x()) || !Float.isFinite(v.y()) || !Float.isFinite(v.z()))
                throw new IllegalArgumentException("Transform must be finite");
        if (scale.x() < .01f || scale.y() < .01f || scale.z() < .01f)
            throw new IllegalArgumentException("Scale must be >= 0.01 on every axis");
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
        double x = Math.toRadians(rotation.x()),
                y = Math.toRadians(rotation.y()),
                z = Math.toRadians(rotation.z());
        float sx = (float) Math.sin(x),
                cx = (float) Math.cos(x),
                sy = (float) Math.sin(y),
                cy = (float) Math.cos(y),
                sz = (float) Math.sin(z),
                cz = (float) Math.cos(z);
        a = cz * cy;
        b = cz * sy * sx - sz * cx;
        c = cz * sy * cx + sz * sx;
        d = sz * cy;
        e = sz * sy * sx + cz * cx;
        f = sz * sy * cx - cz * sx;
        g = -sy;
        h = cy * sx;
        i = cy * cx;
    }

    public Vec3 point(Vec3 p) {
        return vector(p).add(position);
    }

    public Vec3 vector(Vec3 p) {
        float x = p.x() * scale.x(), y = p.y() * scale.y(), z = p.z() * scale.z();
        return new Vec3(a * x + b * y + c * z, d * x + e * y + f * z, g * x + h * y + i * z);
    }

    public Vec3 inversePoint(Vec3 p) {
        return inverseVector(p.sub(position));
    }

    public Vec3 inverseVector(Vec3 p) {
        return new Vec3(
                (a * p.x() + d * p.y() + g * p.z()) / scale.x(),
                (b * p.x() + e * p.y() + h * p.z()) / scale.y(),
                (c * p.x() + f * p.y() + i * p.z()) / scale.z());
    }

    public Vec3 normal(Vec3 n) {
        float x = n.x() / scale.x(), y = n.y() / scale.y(), z = n.z() / scale.z();
        return new Vec3(a * x + b * y + c * z, d * x + e * y + f * z, g * x + h * y + i * z)
                .normalized();
    }

    /**
     * Compose parent and local positive TRS. General affine shear/reflection is deliberately
     * unsupported by the renderer; such compositions are rejected instead of approximated.
     */
    public static Transform compose(Transform parent, Transform local) {
        if (parent == null || local == null)
            throw new IllegalArgumentException("Transforms cannot be null");
        double[][] p = linear(parent), l = linear(local), m = new double[3][3];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) for (int k = 0; k < 3; k++) m[r][c] += p[r][k] * l[k][c];
        double sx = columnLength(m, 0), sy = columnLength(m, 1), sz = columnLength(m, 2);
        if (!finitePositive(sx) || !finitePositive(sy) || !finitePositive(sz))
            throw new IllegalArgumentException("Hierarchy transform is singular or non-finite");
        for (int a = 0; a < 3; a++)
            for (int b = a + 1; b < 3; b++) {
                double dot = 0;
                for (int r = 0; r < 3; r++) dot += m[r][a] * m[r][b];
                double limit = COMPOSITION_TOLERANCE * columnLength(m, a) * columnLength(m, b);
                if (Math.abs(dot) > limit)
                    throw new IllegalArgumentException(
                            "Hierarchy transform would introduce unsupported shear");
            }
        double det =
                m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (!(det > 0))
            throw new IllegalArgumentException("Hierarchy transform reflection is unsupported");
        double[][] r = new double[3][3];
        double[] s = {sx, sy, sz};
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++) r[row][col] = m[row][col] / s[col];
        double y = Math.asin(Math.clamp(-r[2][0], -1, 1)), cy = Math.cos(y), x, z;
        if (Math.abs(cy) > 1e-7) {
            x = Math.atan2(r[2][1], r[2][2]);
            z = Math.atan2(r[1][0], r[0][0]);
        } else {
            z = 0;
            x = y > 0 ? Math.atan2(r[0][1], r[1][1]) : Math.atan2(-r[0][1], r[1][1]);
        }
        var rotation =
                new Vec3(
                        (float) Math.toDegrees(x),
                        (float) Math.toDegrees(y),
                        (float) Math.toDegrees(z));
        var scale = new Vec3((float) sx, (float) sy, (float) sz);
        return new Transform(parent.point(local.position), rotation, scale);
    }

    private static double[][] linear(Transform t) {
        return new double[][] {
            {t.a * t.scale.x(), t.b * t.scale.y(), t.c * t.scale.z()},
            {t.d * t.scale.x(), t.e * t.scale.y(), t.f * t.scale.z()},
            {t.g * t.scale.x(), t.h * t.scale.y(), t.i * t.scale.z()}
        };
    }

    private static double columnLength(double[][] m, int c) {
        return Math.sqrt(m[0][c] * m[0][c] + m[1][c] * m[1][c] + m[2][c] * m[2][c]);
    }

    private static boolean finitePositive(double v) {
        return Double.isFinite(v) && v >= .01;
    }

    /** Uniform positive scale required when a node transform scales camera optical distances. */
    public float uniformScale() {
        float largest = Math.max(scale.x(), Math.max(scale.y(), scale.z()));
        if (Math.abs(scale.x() - scale.y()) > COMPOSITION_TOLERANCE * largest
                || Math.abs(scale.x() - scale.z()) > COMPOSITION_TOLERANCE * largest)
            throw new IllegalArgumentException("Camera hierarchy requires uniform world scale");
        return (scale.x() + scale.y() + scale.z()) / 3;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Transform transform
                && position.equals(transform.position)
                && rotation.equals(transform.rotation)
                && scale.equals(transform.scale);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(position, rotation, scale);
    }

    @Override
    public String toString() {
        return "position=" + position + " rotation=" + rotation + " scale=" + scale;
    }
}
