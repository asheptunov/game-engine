package math;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;

public class IntersectionTest {
    private static final float EPS = 1e-5f;

    private static void assertClose(Vec3 expected, Vec3 actual) {
        if (Math.abs(expected.x() - actual.x()) > EPS
                || Math.abs(expected.y() - actual.y()) > EPS
                || Math.abs(expected.z() - actual.z()) > EPS) {
            throw new RuntimeException("Expected ~" + expected + " but got " + actual);
        }
    }

    @Test
    void normalIncidenceReflectsBackward() {
        // Ray going +z hits plane normal +z. Reflected direction should be -z.
        var incoming = new Ray(Vec3.ZERO, new Vec3(0, 0, 1));
        var hit = new Intersection(new Vec3(0, 0, 5), new Vec3(0, 0, 1), 5, incoming);
        assertClose(new Vec3(0, 0, -1), hit.reflectDirection());
    }

    @Test
    void grazingIncidenceUnchanged() {
        // Ray going +x hits surface with normal +z. Direction is parallel to surface — reflection ≈ incoming.
        var incoming = new Ray(Vec3.ZERO, new Vec3(1, 0, 0));
        var hit = new Intersection(new Vec3(5, 0, 0), new Vec3(0, 0, 1), 5, incoming);
        assertClose(new Vec3(1, 0, 0), hit.reflectDirection());
    }

    @Test
    void fortyFiveDegreeReflection() {
        // Ray going (1,0,-1)/sqrt2 hits floor with normal +y? No, easier: ray going (1,0,-1) normalized hits surface
        // with normal +z. Reflected should be (1,0,1) normalized.
        var dir = new Vec3(1, 0, -1).normalized();
        var incoming = new Ray(new Vec3(0, 0, 10), dir);
        var hit = new Intersection(new Vec3(10, 0, 0), new Vec3(0, 0, 1), 0, incoming);
        var expected = new Vec3(1, 0, 1).normalized();
        assertClose(expected, hit.reflectDirection());
    }

    @Test
    void reflectRayOriginsAtHitPoint() {
        var incoming = new Ray(Vec3.ZERO, new Vec3(0, 0, 1));
        var hit = new Intersection(new Vec3(0, 0, 5), new Vec3(0, 0, 1), 5, incoming);
        assertEquals(new Vec3(0, 0, 5), hit.reflectRay().origin());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
