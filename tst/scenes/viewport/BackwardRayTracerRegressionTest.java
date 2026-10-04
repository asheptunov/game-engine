package scenes.viewport;

import harness.SuiteRunner;
import harness.Test;
import math.Intersection;
import math.Ray;
import math.Vec3;
import scenes.viewport.lights.PointLight;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.Tri;
import java.util.Random;
import static harness.Assertions.*;

/** Independent object-based reference for checking the optimized tracing path. */
public class BackwardRayTracerRegressionTest {
    private static ViewportState scene(int size) {
        return new ViewportState(new Rect(new Vec3(-.5f, -.5f, 0),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0)), size, size);
    }

    @Test void defaultAndCloseImagesMatchReference() {
        for (int cameraZ : new int[]{-4, -1, 8, 12}) {
            var state = scene(64);
            state.eye(new Vec3(0, 0, cameraZ));
            state.cameraSensor(new Rect(new Vec3(-.5f, -.5f, cameraZ + 1),
                    new Vec3(1, 0, 0), new Vec3(0, 1, 0)));
            state.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
            state.addLight(new PointLight(new Vec3(0, 0, 5)));
            compareImage(state);
        }
    }

    @Test void randomizedMultipleSurfacesAndLightsMatchReference() {
        var random = new Random(94721);
        for (int scene = 0; scene < 16; scene++) {
            var state = scene(32);
            for (int i = 0; i < 6; i++) {
                var a = vector(random);
                var e1 = vector(random).scale(.6f);
                var e2 = vector(random).scale(.6f);
                state.addObject(i % 2 == 0 ? new Tri(a, a.add(e1), a.add(e2)) : new Rect(a, e1, e2));
            }
            state.addLight(new PointLight(new Vec3(-3, 2, -2)));
            state.addLight(new PointLight(new Vec3(3, -2, 5)));
            compareImage(state);
        }
    }

    @Test void selfIsSkippedButDistinctOccludersStillCastShadows() {
        var state = scene(8);
        state.addObject(new Rect(new Vec3(-2, -2, 2), new Vec3(4, 0, 0), new Vec3(0, 4, 0)));
        state.addLight(new PointLight(new Vec3(2, 0, 0)));
        var ray = new Ray(state.eye(), new Vec3(0, 0, 1));
        var tracer = new BackwardRayTracer(state);
        assertTrue(tracer.shade(ray) > 0);
        assertEquals(0L, tracer.shadowTests());
        // Off the primary ray, across the diagonal segment from (0,0,2) to (2,0,0).
        var blocker = new Rect(new Vec3(.75f, -.25f, 1), new Vec3(.5f, 0, 0), new Vec3(0, .5f, 0));
        state.addObject(blocker);
        assertEquals(0f, tracer.shade(ray));
        assertTrue(tracer.shadowTests() > 0);
        state.objects().remove(blocker);
        // The same direction, but beyond the light: must not cast a shadow.
        state.addObject(new Rect(new Vec3(2.75f, -.25f, -1), new Vec3(.5f, 0, 0), new Vec3(0, .5f, 0)));
        assertTrue(tracer.shade(ray) > 0);
    }

    @Test void backFacingLightSkipsVisibilityAndStateChangesAreObserved() {
        var state = scene(8);
        state.addObject(new Rect(new Vec3(-2, -2, 2), new Vec3(4, 0, 0), new Vec3(0, 4, 0)));
        state.addLight(new PointLight(new Vec3(0, 0, 5)));
        var tracer = new BackwardRayTracer(state);
        tracer.trace();
        assertTrue(tracer.primaryHits() > 0);
        assertEquals(0, tracer.shadowRays());
        assertEquals(0L, tracer.shadowTests());
        state.lights().clear(); state.addLight(new PointLight(new Vec3(0, 0, 0)));
        assertTrue(tracer.trace()[4][4] > 0);
        state.objects().clear();
        assertEquals(0f, tracer.trace()[4][4]);
        assertEquals(0, tracer.primaryHits());
    }

    private static Vec3 vector(Random r) {
        return new Vec3(r.nextFloat() * 6 - 3, r.nextFloat() * 6 - 3, r.nextFloat() * 6);
    }
    private static void compareImage(ViewportState state) {
        var actual = new BackwardRayTracer(state).trace();
        var sensor = state.cameraSensor();
        for (int y = 0; y < actual.length; y++) for (int x = 0; x < actual[y].length; x++) {
            var pixel = sensor.origin().add(sensor.edge1().scale((x + .5f) / state.sensorPixelsW()))
                    .add(sensor.edge2().scale((y + .5f) / state.sensorPixelsH()));
            float expected = reference(state, new Ray(state.eye(), pixel.sub(state.eye()).normalized()));
            if (Math.abs(expected - actual[y][x]) > 1e-6f) {
                throw new AssertionError("pixel " + x + "," + y + ": " + expected + " != " + actual[y][x]);
            }
        }
    }
    private static float reference(ViewportState state, Ray ray) {
        Intersection hit = null;
        for (var object : state.objects()) {
            var candidate = object.intersect(ray);
            if (candidate.isPresent() && (hit == null || candidate.get().distance() < hit.distance())) hit = candidate.get();
        }
        if (hit == null) return 0;
        var n = hit.normal();
        if (n.dot(ray.direction()) > 0) n = n.negate();
        float result = 0;
        for (var light : state.lights()) {
            if (!(light instanceof PointLight point)) continue;
            var toLight = point.position().sub(hit.point());
            float distance = toLight.length();
            if (distance < 1e-3f) continue;
            var direction = toLight.scale(1f / distance);
            var shadow = new Ray(hit.point().add(direction.scale(1e-3f)), direction);
            boolean blocked = false;
            for (var object : state.objects()) {
                var candidate = object.intersect(shadow);
                if (candidate.isPresent() && candidate.get().distance() < distance - 1e-3f) { blocked = true; break; }
            }
            if (!blocked) result += Math.max(0, n.dot(direction));
        }
        return result;
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
