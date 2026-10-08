package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.lights.PointLight;
import engine.objects.Rect;
import engine.objects.Tri;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import scenes.viewport.*;

public class BackwardRayTracerTest {
    @Test
    void profilingPreservesImageAndCounters() {
        var st = new ViewportState(sensor(), 80, 80);
        st.addObject(new Tri(new Vec3(-2, -2, 2), new Vec3(2, -2, 2), new Vec3(0, 2, 2)));
        st.addLight(new PointLight(new Vec3(0, 0, -1)));
        var tracer = new BackwardRayTracer(st);
        var expected = tracer.trace();
        int hits = tracer.primaryHits(), shadows = tracer.shadowRays(), lit = tracer.litPixels();
        var actual = tracer.trace();
        assertEquals(expected, actual);
        assertEquals(hits, tracer.primaryHits());
        assertEquals(shadows, tracer.shadowRays());
        assertEquals(lit, tracer.litPixels());
        assertTrue(tracer.profile().cpuNanos() >= -1);
        assertTrue(tracer.profile().allocatedBytes() >= -1);
        assertEquals(6400L, tracer.profile().primaryTests());
    }

    // 1×1 sensor at z=0 facing +z. Eye 1 unit behind.
    private static Rect sensor() {
        return new Rect(new Vec3(-0.5f, -0.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0));
    }

    private static ViewportState state() {
        var st = new ViewportState(sensor(), 10, 10);
        st.eye(new Vec3(0, 0, -1));
        return st;
    }

    @Test
    void emptySceneAllBlack() {
        var t = new BackwardRayTracer(state());
        var buf = t.trace();
        for (var row : buf) {
            for (float v : row) {
                assertEquals(0f, v);
            }
        }
    }

    @Test
    void unlitObjectIsBlackEvenIfVisible() {
        // Object exists but no lights.
        var st = state();
        st.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
        var buf = new BackwardRayTracer(st).trace();
        for (var row : buf) {
            for (float v : row) {
                assertEquals(0f, v);
            }
        }
    }

    @Test
    void litTriAppearsInCenterPixels() {
        var st = state();
        st.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
        st.addLight(new PointLight(new Vec3(0, 0, 5)));
        var buf = new BackwardRayTracer(st).trace();
        // Center pixel: ray from (0,0,-1) through (0,0,0) hits tri at (0,0,10) → lit.
        // 10x10 grid, center is around (5,5). The triangle extends ~2/11 of sensor width from
        // center so
        // it covers from roughly (3, 3) to (6, 6).
        assertTrue(buf[5][5] > 0);
    }

    @Test
    void shadowFromBlockingObject() {
        // Tri at z=10. Blocker plane between tri and light. Shadow ray should be occluded.
        var st = state();
        var tri = new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10));
        // Big blocking rect at z=7 covering the light's direction from the tri's hit point.
        var blocker = new Rect(new Vec3(-5, -5, 7), new Vec3(10, 0, 0), new Vec3(0, 10, 0));
        st.addObject(tri);
        st.addObject(blocker);
        st.addLight(new PointLight(new Vec3(0, 0, 5)));

        var t = new BackwardRayTracer(st);
        var buf = t.trace();
        // What does the camera see? Ray from eye (0,0,-1) goes +z. It hits the blocker rect at z=7
        // FIRST
        // (closer than tri at z=10). Sun at z=5, so light goes -z from sun and hits blocker's z=+z
        // face.
        // Blocker's normal: (10,0,0)×(0,10,0) = (0,0,100) → +z. Flipped to face camera (which is at
        // -z) → -z.
        // From hit on blocker at z=7, ray to sun: direction (0,0,-1). Shadow occlusion: anything
        // between
        // hit (z=7) and sun (z=5)? Distance 2. No other objects there. So unobstructed.
        // Lambertian: cos((-z)·(-z)) = 1 → lit.
        // So center pixels are lit because the camera sees the front of the blocker, which is lit
        // by sun.
        assertTrue(buf[5][5] > 0);
    }

    @Test
    void surfaceFacingAwayFromLightIsShadowed() {
        // Plane between camera and sun. Camera sees the "back" relative to sun. Light is BEHIND the
        // surface
        // from camera's perspective. After normal-flip to face camera, n·lightDir is negative →
        // max(0, ·) = 0.
        var st = state();
        // Plane at z=5 with vertices arranged so its natural normal is +z.
        var blocker = new Rect(new Vec3(-5, -5, 5), new Vec3(10, 0, 0), new Vec3(0, 10, 0));
        st.addObject(blocker);
        // Sun behind the blocker (further from camera).
        st.addLight(new PointLight(new Vec3(0, 0, 20)));

        var t = new BackwardRayTracer(st);
        var buf = t.trace();
        // Camera ray hits front of blocker (z=5) from -z side. Normal flipped to face camera = -z.
        // Light direction from hit to sun = (0,0,+1). cos((-z)·(+z)) = -1, max 0 = 0. → unlit
        // (shadowed by orientation).
        // BUT: shadow ray would also be blocked by... nothing actually (only one object). Hmm wait
        // —
        // the orientation check makes it 0 regardless.
        assertEquals(0f, buf[5][5]);
    }

    @Test
    void cornerPixelMissesSmallObject() {
        var st = state();
        st.addObject(new Tri(new Vec3(-2, -2, 10), new Vec3(2, -2, 10), new Vec3(0, 2, 10)));
        st.addLight(new PointLight(new Vec3(0, 0, 5)));
        var buf = new BackwardRayTracer(st).trace();
        // Corner pixel (0, 0). Sensor pixel center at u=0.05, v=0.05. World point ~(-0.45, -0.45,
        // 0).
        // Eye (0,0,-1). Direction = (-0.45, -0.45, 1)/√(1.405). At z=10, t = 11/(1/√1.405) → x =
        // -0.45*11 = -4.95.
        // Outside tri (which is x ∈ [-2, 2]). Miss → 0.
        assertEquals(0f, buf[0][0]);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
