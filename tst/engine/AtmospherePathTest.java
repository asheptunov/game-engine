package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

/** Surface brightness, solar shadows, indirect sky energy and disabled-path identity. */
public final class AtmospherePathTest {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static ViewportState state(Atmosphere air) {
        var state =
                new ViewportState(
                        new Rect(new Vec3(-1, -1, -2), new Vec3(2, 0, 0), new Vec3(0, 2, 0)),
                        64,
                        64);
        state.sky(Sky.BLACK.withAtmosphere(air));
        state.instances().add(cube("surface", Vec3.ZERO));
        return state;
    }

    private static SceneInstance cube(String name, Vec3 position) {
        return new SceneInstance(
                name,
                BoxGeometry.UNIT,
                new Transform(position, Vec3.ZERO, WHITE),
                new Material("white", WHITE));
    }

    private static void near(double expected, double actual, double tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance);
    }

    @Test
    void surfaceCompositionAndGeometryShadowMatchIndependentReference() {
        var air =
                new Atmosphere(
                        Atmosphere.Parameters.clearAir(), new Vec3(0, .2f, -1), new Vec3(3, 3, 3));
        var state = state(air);
        var reference =
                new AtmosphereTransport.Scratch(new AtmosphereTransport.Quality(512, 384, true));
        AtmosphereTransport.segment(air, 0, 0, 0, 1, 2, false, reference);
        AtmosphereTransport.sunlight(air, 0, reference);
        var ray = new Ray(new Vec3(0, 0, -3), new Vec3(0, 0, 1));
        try (var tracer = new DirectRgbTracer(state)) {
            var result = tracer.radiance(ray, 0);
            for (int c = 0; c < 3; c++) {
                double surface = reference.sunlight[c] * -air.solarDirection().z() / Math.PI;
                near(reference.transmission[c] * surface + reference.radiance[c], result[c], .002);
            }
            state.instances()
                    .add(
                            cube(
                                    "solar blocker",
                                    new Vec3(0, 0, -1).add(air.solarDirection().scale(10))));
            var shadow = tracer.radiance(ray, 0);
            for (int c = 0; c < 3; c++) {
                near(reference.radiance[c], shadow[c], .0001);
            }
            assertTrue(shadow[0] < result[0] * .01);
        }
    }

    @Test
    void indirectSkyIlluminationCountsSkyOnceWithoutSolarDiscEnergy() {
        var air =
                new Atmosphere(
                        Atmosphere.Parameters.clearAir(), new Vec3(0, 1, 0), new Vec3(3, 3, 3));
        var state = state(air);
        state.pathDepth(1);
        var cameraSegment = new AtmosphereTransport.Scratch();
        AtmosphereTransport.segment(air, 3, 0, -1, 0, 2, false, cameraSegment);
        AtmosphereTransport.sunlight(air, 1, cameraSegment);
        double[] sky = new double[3];
        var sample = new AtmosphereTransport.Scratch();
        for (int u = 0; u < 32; u++) {
            float radius = (float) Math.sqrt((u + .5) / 32);
            float y = (float) Math.sqrt(1 - radius * radius);
            for (int v = 0; v < 64; v++) {
                double phi = 2 * Math.PI * (v + .5) / 64;
                AtmosphereTransport.segment(
                        air,
                        1.001f,
                        radius * (float) Math.cos(phi),
                        y,
                        radius * (float) Math.sin(phi),
                        Float.POSITIVE_INFINITY,
                        false,
                        sample);
                for (int c = 0; c < 3; c++) {
                    sky[c] += sample.radiance[c] / 2048.;
                }
            }
        }
        double[] sum = new double[3];
        try (var tracer = new DirectRgbTracer(state)) {
            var ray = new Ray(new Vec3(0, 3, 0), new Vec3(0, -1, 0));
            for (int i = 0; i < 4096; i++) {
                var value = tracer.radiance(ray, i);
                for (int c = 0; c < 3; c++) {
                    sum[c] += value[c] / 4096.;
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            double expected =
                    cameraSegment.radiance[c]
                            + cameraSegment.transmission[c]
                                    * (cameraSegment.sunlight[c] / Math.PI + sky[c]);
            near(expected, sum[c], .01);
        }
    }

    @Test
    void editsDisableRestoreLegacyAndDisableTemporalPresentation() {
        var state = state(null);
        state.sky(new Sky(new Vec3(.1f, .2f, .3f), new Vec3(.3f, .5f, .7f)));
        var legacy = state.sky();
        state.pathDepth(1);
        var ray = new Ray(new Vec3(0, 3, 0), new Vec3(0, -1, 0));
        try (var tracer = new DirectRgbTracer(state)) {
            var original = tracer.radiance(ray, 4);
            state.temporal(true);
            state.sky(
                    legacy.withAtmosphere(
                            new Atmosphere(
                                    Atmosphere.Parameters.clearAir(),
                                    new Vec3(0, 1, 0),
                                    new Vec3(3, 3, 3))));
            assertTrue(!state.temporalEffective());
            assertTrue(tracer.radiance(ray, 4)[0] > original[0]);
            state.sky(legacy);
            assertTrue(state.temporalEffective());
            assertEquals(original, tracer.radiance(ray, 4));
        }
    }
}
