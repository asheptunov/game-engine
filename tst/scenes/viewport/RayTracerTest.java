package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Ray;
import math.Vec3;
import engine.lights.PointLight;
import engine.objects.Rect;
import engine.objects.Tri;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class RayTracerTest {
    // Unit-square sensor at z=0, normal +z (facing into +z half-space toward the scene).
    private static Rect sensor() {
        return new Rect(new Vec3(-0.5f, -0.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0));
    }

    private static ViewportState state() {
        return new ViewportState(sensor(), 10, 10);
    }

    @Test
    void directFrontHitRecorded() {
        // Ray from z=5 going -z hits sensor center → front (rayDir·normal = -1 < 0).
        var tracer = new RayTracer(state(), 0L);
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, 5), new Vec3(0, 0, -1)));
        assertTrue(hit.isPresent());
        // Sensor: origin (-0.5,-0.5), edges (1,0,0)(0,1,0). Hit point (0,0,0). u=0.5, v=0.5. → pixel (5,5).
        assertEquals(5, hit.get().x());
        assertEquals(5, hit.get().y());
    }

    @Test
    void directBackHitDiscarded() {
        // Ray from z=-5 going +z hits sensor from back → discarded.
        var tracer = new RayTracer(state(), 0L);
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, -5), new Vec3(0, 0, 1)));
        assertEquals(true, hit.isEmpty());
    }

    @Test
    void missingEverythingDiscarded() {
        var tracer = new RayTracer(state(), 0L);
        // Ray going +x from origin — sensor's plane is z=0 and ray stays on it; parallel → no intersection.
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, 1), new Vec3(1, 0, 0)));
        assertEquals(true, hit.isEmpty());
    }

    @Test
    void reflectsOffMirrorIntoCamera() {
        // Mirror (Rect) at z=10 facing -z. Ray from z=5 going +z hits mirror, reflects to -z, hits camera front.
        var st = state();
        var mirror = new Rect(new Vec3(-1, -1, 10), new Vec3(2, 0, 0), new Vec3(0, 2, 0));
        // Note: mirror's natural normal = (2,0,0)×(0,2,0) = (0,0,4) → +z. That's facing AWAY from camera,
        // but reflection physics is normal-direction-agnostic, so this still works.
        st.objects().add(mirror);

        var tracer = new RayTracer(st, 0L);
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, 5), new Vec3(0, 0, 1)));
        assertTrue(hit.isPresent());
        assertEquals(5, hit.get().x());
        assertEquals(5, hit.get().y());
    }

    @Test
    void maxBouncesEnforced() {
        // Two parallel mirrors trap a ray indefinitely — must stop at maxBounces.
        var st = state();
        st.maxBounces(2);
        // Mirror A at z=5, Mirror B at z=15. Ray bounces between them and never reaches camera.
        st.objects().add(new Rect(new Vec3(-1, -1, 5), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
        st.objects().add(new Rect(new Vec3(-1, -1, 15), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));

        var tracer = new RayTracer(st, 0L);
        // Ray starts above mirror A, going +z. Hits B, bounces to -z, hits A, bounces to +z, etc.
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, 10), new Vec3(0, 0, 1)));
        assertEquals(true, hit.isEmpty());
    }

    @Test
    void cameraOccludesObjectsBehindIt() {
        // Object behind the camera (z < 0); ray going -z hits camera first, doesn't reach object.
        var st = state();
        // Tri at z=-5, in same path as ray
        st.objects().add(new Tri(new Vec3(-1, -1, -5), new Vec3(1, -1, -5), new Vec3(0, 1, -5)));

        var tracer = new RayTracer(st, 0L);
        var hit = tracer.traceFrom(new Ray(new Vec3(0, 0, 5), new Vec3(0, 0, -1)));
        // Hits camera at z=0 from +z (front), records pixel. Object behind is irrelevant.
        assertTrue(hit.isPresent());
    }

    @Test
    void traceProducesNonEmptyBufferForFullScene() {
        // Sun between camera and tri. Some light samples should reach the camera via reflection.
        var st = state();
        st.samplesPerLight(5000);
        st.maxBounces(3);
        // Tri behind sun, facing camera
        st.objects().add(new Tri(new Vec3(-3, -3, 20), new Vec3(3, -3, 20), new Vec3(0, 3, 20)));
        st.lights().add(new PointLight(new Vec3(0, 0, 10)));

        var tracer = new RayTracer(st, 42L);
        var buf = tracer.trace();
        int totalHits = 0;
        for (int y = 0; y < buf.length; y++) {
            for (int x = 0; x < buf[y].length; x++) {
                totalHits += (int) buf[y][x];
            }
        }
        // Direct ray from sun in -z direction goes through camera plane → records pixel.
        // Indirect rays: hit tri, reflect, some come back. Either way, we expect non-zero hits.
        assertTrue(totalHits > 0);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
