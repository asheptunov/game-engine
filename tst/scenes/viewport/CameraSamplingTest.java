package engine;

import static harness.Assertions.*;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import scenes.viewport.*;

public class CameraSamplingTest {
    private static ViewportState state(int workers, int tile, int batch) {
        var s =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        67,
                        73);
        ScenePresets.load(s, "bounce-room");
        s.workers(workers);
        s.tileSize(tile);
        s.samplesPerFrame(batch);
        s.sampleTarget(3);
        s.seed(812);
        return s;
    }

    private static float[] stream(long seed, int pixel, long sample) {
        var sampler = new DirectRgbTracer.Sampler();
        sampler.reset(seed, pixel, sample);
        var values = new float[8];
        for (int i = 0; i < values.length; i++) values[i] = sampler.next();
        return values;
    }

    @Test
    void capturedCameraScramblesSampleZeroWithoutTimeOrScheduling() {
        var s = state(1, 32, 1);
        var eye = s.eye();
        var sensor = s.cameraSensor();
        long seed = DirectRgbTracer.cameraSeed(s.seed(), eye, sensor);
        var first = stream(seed, 100, 0);
        assertEquals(first, stream(DirectRgbTracer.cameraSeed(s.seed(), eye, sensor), 100, 0));
        assertNotEquals(first, stream(seed, 100, 1));
        assertNotEquals(
                first,
                stream(
                        DirectRgbTracer.cameraSeed(
                                s.seed(), eye.add(new Vec3(.0001f, 0, 0)), sensor),
                        100,
                        0));
        for (var changed :
                new Rect[] {
                    new Rect(
                            sensor.origin().add(new Vec3(.0001f, 0, 0)),
                            sensor.edge1(),
                            sensor.edge2()),
                    new Rect(
                            sensor.origin(),
                            sensor.edge1().add(new Vec3(0, 0, .0001f)),
                            sensor.edge2()),
                    new Rect(
                            sensor.origin(),
                            sensor.edge1(),
                            sensor.edge2().add(new Vec3(0, 0, .0001f)))
                }) {
            assertNotEquals(
                    first, stream(DirectRgbTracer.cameraSeed(s.seed(), eye, changed), 100, 0));
        }
        assertNotEquals(
                first, stream(DirectRgbTracer.cameraSeed(s.seed() + 1, eye, sensor), 100, 0));
    }

    private static void view(ViewportState s, int index) {
        float x = index * .04f;
        s.eye(new Vec3(x, 0, -1));
        s.cameraSensor(
                new Rect(
                        new Vec3(-.5f + x, -.5f, 0),
                        new Vec3(1, 0, .01f * index),
                        new Vec3(0, 1, 0)));
    }

    private static float[][][] completed(DirectRgbTracer tracer, ViewportState s) {
        do {
            tracer.trace();
        } while (s.accumulatedSamples() < 3);
        return TemporalReconstructionTest.copy(tracer.radianceBuffer());
    }

    @Test
    void movingViewsReproduceAcrossWorkersBatchesAndDifferentVisitOrders() {
        var a = state(1, 256, 1);
        var b = state(Math.min(4, Runtime.getRuntime().availableProcessors()), 8, 2);
        try (var serial = new DirectRgbTracer(a);
                var parallel = new DirectRgbTracer(b)) {
            var original = completed(serial, a);
            for (int index : new int[] {1, 2, 1, 0}) {
                view(a, index);
                view(b, index);
                var result = completed(serial, a);
                assertEquals(result, completed(parallel, b));
                var fresh = state(1, 16, 1);
                view(fresh, index);
                try (var reference = new DirectRgbTracer(fresh)) {
                    assertEquals(result, completed(reference, fresh));
                }
                assertEquals(3L, a.accumulatedSamples());
                assertEquals(812L, serial.profile.seed());
                if (index == 0) assertEquals(original, result);
            }
        }
    }

    @Test
    void pointLightDepthZeroRemainsSeedIndependentPixelCenterDiagnostic() {
        var a = state(1, 32, 1);
        var b = state(1, 32, 1);
        a.pathDepth(0);
        b.pathDepth(0);
        b.seed(999);
        try (var first = new DirectRgbTracer(a);
                var second = new DirectRgbTracer(b)) {
            assertEquals(first.trace(), second.trace());
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
