package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import engine.objects.Rect;
import engine.objects.SceneObject;
import engine.objects.Tri;
import java.util.Random;
import static harness.Assertions.*;

public class TraceSurfaceTest {
    @Test void preparedGeometryMatchesReference() {
        var random = new Random(724);
        for (int shape = 0; shape < 100; shape++) {
            var origin = vector(random);
            var e1 = vector(random);
            var e2 = vector(random);
            SceneObject object = shape % 2 == 0
                    ? new Tri(origin, origin.add(e1), origin.add(e2)) : new Rect(origin, e1, e2);
            var prepared = new TraceSurface(object);
            for (int i = 0; i < 1000; i++) {
                var ray = new Ray(vector(random), vector(random).normalized());
                var reference = object.intersect(ray);
                float distance = prepared.distance(ray);
                assertEquals(reference.isPresent(), Float.isFinite(distance));
                if (reference.isPresent()) {
                    assertEquals(reference.get(), prepared.hit(ray, distance));
                }
            }
        }
    }

    @Test void edgesParallelBehindAndDegenerate() {
        var tri = new Tri(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0));
        var surface = new TraceSurface(tri);
        assertEquals(1f, surface.distance(new Ray(new Vec3(0, 0, -1), new Vec3(0, 0, 1))));
        assertEquals(Float.POSITIVE_INFINITY, surface.distance(new Ray(new Vec3(0, 0, -1), new Vec3(1, 0, 0))));
        assertEquals(Float.POSITIVE_INFINITY, surface.distance(new Ray(new Vec3(0, 0, -1), new Vec3(0, 0, -1))));
        var degenerate = new TraceSurface(new Tri(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO));
        assertEquals(Float.POSITIVE_INFINITY, degenerate.distance(new Ray(new Vec3(0, 0, -1), new Vec3(0, 0, 1))));
    }

    private static Vec3 vector(Random r) {
        return new Vec3(r.nextFloat() * 6 - 3, r.nextFloat() * 6 - 3, r.nextFloat() * 6 - 3);
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
