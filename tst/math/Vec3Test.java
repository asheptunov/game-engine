package math;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class Vec3Test {
    private static final float EPS = 1e-5f;

    private static void assertClose(float expected, float actual) {
        if (Math.abs(expected - actual) > EPS) {
            throw new RuntimeException("Expected ~" + expected + " but got " + actual);
        }
    }

    private static void assertClose(Vec3 expected, Vec3 actual) {
        assertClose(expected.x(), actual.x());
        assertClose(expected.y(), actual.y());
        assertClose(expected.z(), actual.z());
    }

    @Test
    void zeroConstant() {
        assertEquals(new Vec3(0, 0, 0), Vec3.ZERO);
    }

    @Test
    void add() {
        assertEquals(new Vec3(4, 6, 8), new Vec3(1, 2, 3).add(new Vec3(3, 4, 5)));
    }

    @Test
    void sub() {
        assertEquals(new Vec3(-2, -2, -2), new Vec3(1, 2, 3).sub(new Vec3(3, 4, 5)));
    }

    @Test
    void scale() {
        assertEquals(new Vec3(2, 4, 6), new Vec3(1, 2, 3).scale(2));
        assertEquals(Vec3.ZERO, new Vec3(1, 2, 3).scale(0));
    }

    @Test
    void negate() {
        assertEquals(new Vec3(-1, -2, -3), new Vec3(1, 2, 3).negate());
    }

    @Test
    void dotSelfEqualsLengthSq() {
        var v = new Vec3(2, 3, 6);
        assertClose(v.lengthSq(), v.dot(v));
    }

    @Test
    void dotOrthogonalIsZero() {
        assertClose(0, new Vec3(1, 0, 0).dot(new Vec3(0, 1, 0)));
        assertClose(0, new Vec3(0, 1, 0).dot(new Vec3(0, 0, 1)));
        assertClose(0, new Vec3(0, 0, 1).dot(new Vec3(1, 0, 0)));
    }

    @Test
    void dotComputes() {
        assertClose(1*4 + 2*5 + 3*6, new Vec3(1, 2, 3).dot(new Vec3(4, 5, 6)));
    }

    @Test
    void crossBasis() {
        // x × y = z, y × z = x, z × x = y in a right-handed system
        assertClose(new Vec3(0, 0, 1), new Vec3(1, 0, 0).cross(new Vec3(0, 1, 0)));
        assertClose(new Vec3(1, 0, 0), new Vec3(0, 1, 0).cross(new Vec3(0, 0, 1)));
        assertClose(new Vec3(0, 1, 0), new Vec3(0, 0, 1).cross(new Vec3(1, 0, 0)));
    }

    @Test
    void crossAntiCommutative() {
        var a = new Vec3(1, 2, 3);
        var b = new Vec3(4, 5, 6);
        assertClose(a.cross(b), b.cross(a).negate());
    }

    @Test
    void crossOfParallelIsZero() {
        assertClose(Vec3.ZERO, new Vec3(1, 2, 3).cross(new Vec3(2, 4, 6)));
    }

    @Test
    void lengthSq() {
        assertClose(1*1 + 2*2 + 3*3, new Vec3(1, 2, 3).lengthSq());
    }

    @Test
    void length() {
        // (2,3,6) → sqrt(49) = 7
        assertClose(7, new Vec3(2, 3, 6).length());
    }

    @Test
    void normalized() {
        var u = new Vec3(2, 3, 6).normalized();
        assertClose(1, u.length());
        // direction preserved
        assertClose(new Vec3(2f/7, 3f/7, 6f/7), u);
    }

    @Test
    void normalizeZeroThrows() {
        try {
            Vec3.ZERO.normalized();
            throw new RuntimeException("expected ArithmeticException");
        } catch (ArithmeticException expected) {
            // ok
        }
    }

    @Test
    void recordEqualityAndImmutability() {
        var a = new Vec3(1, 2, 3);
        var b = new Vec3(1, 2, 3);
        assertEquals(a, b);
        // Operations return new instances; originals unchanged
        var sum = a.add(b);
        assertEquals(new Vec3(1, 2, 3), a);
        assertEquals(new Vec3(2, 4, 6), sum);
        assertTrue(a != sum);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
