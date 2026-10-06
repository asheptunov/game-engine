package engine.objects;

import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

public class RectTest {
    private static final float EPS = 1e-4f;

    // Unit square in z=0 plane: corners (0,0), (1,0), (1,1), (0,1). Natural normal: edge1×edge2 = +z.
    private final Rect rect = new Rect(
            new Vec3(0, 0, 0),
            new Vec3(1, 0, 0),
            new Vec3(0, 1, 0));

    @Test
    void normalIsPositiveZ() {
        assertEquals(new Vec3(0, 0, 1), rect.normal());
    }

    @Test
    void hitInsideCenter() {
        var ray = new Ray(new Vec3(0.5f, 0.5f, 5), new Vec3(0, 0, -1));
        var hit = rect.intersect(ray);
        assertTrue(hit.isPresent());
        assertTrue(Math.abs(hit.get().distance() - 5f) < EPS);
        assertTrue(hit.get().point().sub(new Vec3(0.5f, 0.5f, 0)).length() < EPS);
    }

    @Test
    void hitInsideOppositeCorner() {
        // Triangles would miss (0.7,0.7), Rect hits.
        var ray = new Ray(new Vec3(0.7f, 0.7f, 5), new Vec3(0, 0, -1));
        assertTrue(rect.intersect(ray).isPresent());
    }

    @Test
    void missBeyondUOne() {
        var ray = new Ray(new Vec3(1.1f, 0.5f, 5), new Vec3(0, 0, -1));
        assertEquals(true, rect.intersect(ray).isEmpty());
    }

    @Test
    void missBeyondVOne() {
        var ray = new Ray(new Vec3(0.5f, 1.1f, 5), new Vec3(0, 0, -1));
        assertEquals(true, rect.intersect(ray).isEmpty());
    }

    @Test
    void missNegativeU() {
        var ray = new Ray(new Vec3(-0.1f, 0.5f, 5), new Vec3(0, 0, -1));
        assertEquals(true, rect.intersect(ray).isEmpty());
    }

    @Test
    void parallelRayMisses() {
        var ray = new Ray(new Vec3(0.5f, 0.5f, 5), new Vec3(1, 0, 0));
        assertEquals(true, rect.intersect(ray).isEmpty());
    }

    @Test
    void rayFromBehindRectStillIntersects() {
        var ray = new Ray(new Vec3(0.5f, 0.5f, -5), new Vec3(0, 0, 1));
        var hit = rect.intersect(ray);
        assertTrue(hit.isPresent());
    }

    @Test
    void isFrontHitCorrectlyDetectsSide() {
        // Ray going -z (toward the rect's sensing side, since normal = +z) → front hit
        var front = new Ray(new Vec3(0.5f, 0.5f, 5), new Vec3(0, 0, -1));
        assertTrue(rect.isFrontHit(front));
        // Ray going +z (toward back side) → not front hit
        var back = new Ray(new Vec3(0.5f, 0.5f, -5), new Vec3(0, 0, 1));
        assertFalse(rect.isFrontHit(back));
    }

    @Test
    void uvMapsCorner() {
        // (0,0,0) → u=0, v=0
        assertTrue(Math.abs(rect.u(new Vec3(0, 0, 0))) < EPS);
        assertTrue(Math.abs(rect.v(new Vec3(0, 0, 0))) < EPS);
        // (1,1,0) → u=1, v=1
        assertTrue(Math.abs(rect.u(new Vec3(1, 1, 0)) - 1f) < EPS);
        assertTrue(Math.abs(rect.v(new Vec3(1, 1, 0)) - 1f) < EPS);
    }

    @Test
    void uvMapsCenter() {
        assertTrue(Math.abs(rect.u(new Vec3(0.5f, 0.5f, 0)) - 0.5f) < EPS);
        assertTrue(Math.abs(rect.v(new Vec3(0.5f, 0.5f, 0)) - 0.5f) < EPS);
    }

    @Test
    void uvWithNonUnitEdges() {
        // Rect with 2-unit edges. (1, 1, 0) midpoint → u=v=0.5.
        var bigger = new Rect(Vec3.ZERO, new Vec3(2, 0, 0), new Vec3(0, 2, 0));
        assertTrue(Math.abs(bigger.u(new Vec3(1, 1, 0)) - 0.5f) < EPS);
        assertTrue(Math.abs(bigger.v(new Vec3(1, 1, 0)) - 0.5f) < EPS);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
