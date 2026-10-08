package math;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import harness.SuiteRunner;
import harness.Test;

public class PlaneTest {
    private static final float EPS = 1e-4f;

    @Test
    void hitsHeadOn() {
        // Plane at z=5, normal +z. Ray from origin going +z. Hit at t=5.
        var plane = new Plane(new Vec3(0, 0, 5), new Vec3(0, 0, 1));
        var ray = new Ray(Vec3.ZERO, new Vec3(0, 0, 1));
        var t = plane.intersect(ray);
        assertTrue(t.isPresent());
        assertTrue(Math.abs(t.get() - 5f) < EPS);
    }

    @Test
    void hitsFromOtherSide() {
        // Plane at z=5, normal +z. Ray from z=10 going -z. Hit at t=5.
        var plane = new Plane(new Vec3(0, 0, 5), new Vec3(0, 0, 1));
        var ray = new Ray(new Vec3(0, 0, 10), new Vec3(0, 0, -1));
        var t = plane.intersect(ray);
        assertTrue(t.isPresent());
        assertTrue(Math.abs(t.get() - 5f) < EPS);
    }

    @Test
    void parallelRayMisses() {
        // Plane at z=5, normal +z. Ray going +x at z=0. Never hits.
        var plane = new Plane(new Vec3(0, 0, 5), new Vec3(0, 0, 1));
        var ray = new Ray(Vec3.ZERO, new Vec3(1, 0, 0));
        assertEquals(true, plane.intersect(ray).isEmpty());
    }

    @Test
    void rayBehindOriginMisses() {
        // Plane at z=-5, normal +z. Ray from origin going +z. Plane is behind ray.
        var plane = new Plane(new Vec3(0, 0, -5), new Vec3(0, 0, 1));
        var ray = new Ray(Vec3.ZERO, new Vec3(0, 0, 1));
        assertEquals(true, plane.intersect(ray).isEmpty());
    }

    @Test
    void rayAtPlaneSurfaceMisses() {
        // Origin lies exactly on plane → t=0, filtered by EPSILON.
        var plane = new Plane(new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        var ray = new Ray(Vec3.ZERO, new Vec3(0, 0, 1));
        assertEquals(true, plane.intersect(ray).isEmpty());
    }

    @Test
    void obliqueIntersection() {
        // Plane at z=10 normal +z. Ray from origin going (1,0,1)/sqrt(2). t such that
        // 0+t*(1/sqrt2)*z=10 → t=10*sqrt(2).
        var plane = new Plane(new Vec3(0, 0, 10), new Vec3(0, 0, 1));
        var dir = new Vec3(1, 0, 1).normalized();
        var ray = new Ray(Vec3.ZERO, dir);
        var t = plane.intersect(ray);
        assertTrue(t.isPresent());
        assertTrue(Math.abs(t.get() - 10f * (float) Math.sqrt(2)) < EPS);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
