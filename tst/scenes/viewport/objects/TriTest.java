package scenes.viewport.objects;

import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class TriTest {
    private static final float EPS = 1e-4f;

    // Unit triangle in z=0 plane with corners (0,0,0), (1,0,0), (0,1,0). Natural normal: +z.
    private final Tri tri = new Tri(
            new Vec3(0, 0, 0),
            new Vec3(1, 0, 0),
            new Vec3(0, 1, 0));

    @Test
    void normalIsPositiveZ() {
        assertEquals(new Vec3(0, 0, 1), tri.normal());
    }

    @Test
    void hitInsideCentroid() {
        // Ray from (0.3, 0.3, 5) going -z hits at (0.3, 0.3, 0).
        var ray = new Ray(new Vec3(0.3f, 0.3f, 5), new Vec3(0, 0, -1));
        var hit = tri.intersect(ray);
        assertTrue(hit.isPresent());
        assertTrue(Math.abs(hit.get().distance() - 5f) < EPS);
        assertTrue(hit.get().point().sub(new Vec3(0.3f, 0.3f, 0)).length() < EPS);
    }

    @Test
    void missOutsideBeyondHypotenuse() {
        // Point (0.7, 0.7) is outside triangle (u+v > 1).
        var ray = new Ray(new Vec3(0.7f, 0.7f, 5), new Vec3(0, 0, -1));
        assertEquals(true, tri.intersect(ray).isEmpty());
    }

    @Test
    void missOutsideNegativeU() {
        var ray = new Ray(new Vec3(-0.1f, 0.5f, 5), new Vec3(0, 0, -1));
        assertEquals(true, tri.intersect(ray).isEmpty());
    }

    @Test
    void missOutsideNegativeV() {
        var ray = new Ray(new Vec3(0.5f, -0.1f, 5), new Vec3(0, 0, -1));
        assertEquals(true, tri.intersect(ray).isEmpty());
    }

    @Test
    void parallelRayMisses() {
        var ray = new Ray(new Vec3(0.3f, 0.3f, 5), new Vec3(1, 0, 0));
        assertEquals(true, tri.intersect(ray).isEmpty());
    }

    @Test
    void rayBehindTriangleMisses() {
        // Ray from (0.3, 0.3, -5) going -z. Triangle is at z=0, in front (-z would go away from it).
        var ray = new Ray(new Vec3(0.3f, 0.3f, -5), new Vec3(0, 0, -1));
        assertEquals(true, tri.intersect(ray).isEmpty());
    }

    @Test
    void hitFromBackSide() {
        // Ray from below going +z still hits — backside hits are detected. Caller can decide significance.
        var ray = new Ray(new Vec3(0.3f, 0.3f, -5), new Vec3(0, 0, 1));
        var hit = tri.intersect(ray);
        assertTrue(hit.isPresent());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
