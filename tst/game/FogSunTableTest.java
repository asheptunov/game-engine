package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.Atmosphere;
import engine.FogRegion;
import engine.FogSunTable;
import engine.FogTransport;
import engine.Sky;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.List;

public final class FogSunTableTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void interpolationMatchesReferenceAcrossMovingSunAndOverlaps() {
        var random = new java.util.Random(7162);
        double maximum = 0;
        for (double hour : new double[] {5.9, 6, 7, 12, 18, 18.1}) {
            var clock = new DayNightCycle();
            clock.hour(hour);
            var sky =
                    clock.atmosphericLighting(Atmosphere.Parameters.clearAir())
                            .sky()
                            .withFog(List.of(RuinsMist.region(0), RuinsMist.region(1)));
            long start = System.nanoTime();
            var layout = FogSunTable.layout(sky);
            if (layout == null) {
                assertTrue(sky.atmosphere().solarDirection().y() <= .05f);
                continue;
            }
            var table = new FogSunTable(layout);
            System.out.println(
                    "Fog table build hour=" + hour + " ms=" + (System.nanoTime() - start) / 1e6);
            var scratch = new FogTransport.Scratch();
            var reference = new FogTransport.Scratch(24, 192);
            float[] result = new float[3];
            var d = layout.direction();
            for (int i = 0; i < 3000; i++) {
                var p = layout.lower();
                var e = layout.extent();
                float x = p.x() + random.nextFloat() * e.x();
                float y = p.y() + random.nextFloat() * e.y();
                float z = p.z() + random.nextFloat() * e.z();
                scratch.ray.set(x, y, z, d.x(), d.y(), d.z());
                boolean interpolated = table.sample(scratch.ray, result);
                FogTransport.transmission(sky.fog(), scratch.ray, scratch);
                if (!interpolated) {
                    System.arraycopy(scratch.sun, 0, result, 0, 3);
                }
                FogTransport.transmission(sky.fog(), scratch.ray, reference);
                for (int c = 0; c < 3; c++) {
                    maximum = Math.max(maximum, error(result[c], reference.sun[c]));
                }
            }
            assertTrue(!table.sample(scratch.ray.set(-1000, 0, 0, d.x(), d.y(), d.z()), result));
            assertTrue(!table.sample(scratch.ray.set(20, 3, 28, -d.x(), -d.y(), -d.z()), result));
        }
        System.out.println("Fog cache maximum transmittance error=" + maximum);
    }

    private static double error(float actual, float reference) {
        double difference = Math.abs(actual - reference);
        if (difference > .015) {
            throw new AssertionError("Cache transmission=" + actual + ", reference=" + reference);
        }
        return difference;
    }

    @Test
    void keyTracksFogAndDirectionButNotCameraOrRadiance() {
        var sky =
                Sky.BLACK
                        .withAtmosphere(
                                new Atmosphere(
                                        Atmosphere.Parameters.clearAir(),
                                        new Vec3(1, 1, 0),
                                        new Vec3(3, 3, 3)))
                        .withFog(List.of(RuinsMist.region(0)));
        var a = FogSunTable.layout(sky);
        assertEquals(
                a,
                FogSunTable.layout(
                        sky.withAtmosphere(
                                new Atmosphere(
                                        sky.atmosphere().parameters(),
                                        sky.atmosphere().solarDirection(),
                                        new Vec3(1, 1, 1)))));
        assertTrue(
                !a.equals(
                        FogSunTable.layout(
                                sky.withAtmosphere(
                                        new Atmosphere(
                                                sky.atmosphere().parameters(),
                                                new Vec3(1, .1f, 0),
                                                new Vec3(3, 3, 3))))));
        assertTrue(!a.equals(FogSunTable.layout(sky.withFog(List.of(RuinsMist.region(1))))));
        assertEquals(null, FogSunTable.layout(Sky.BLACK));
    }

    @Test
    void unresolvedAndSharpMediaKeepReferenceTransport() {
        var clock = new DayNightCycle();
        clock.hour(7);
        var sky = clock.atmosphericLighting(Atmosphere.Parameters.clearAir()).sky();
        var r = RuinsMist.region(0);
        assertEquals(null, FogSunTable.layout(sky.withFog(List.of(r, r, r))));
        for (float feather : new float[] {0, .05f, .1f}) {
            var sharp =
                    new FogRegion(
                            r.center(),
                            r.halfExtent(),
                            r.yawDegrees(),
                            r.scattering(),
                            r.absorption(),
                            r.density(),
                            r.heightFalloff(),
                            r.anisotropy(),
                            r.variation(),
                            r.frequency(),
                            feather);
            assertEquals(null, FogSunTable.layout(sky.withFog(List.of(sharp))));
        }
        var distant =
                new FogRegion(
                        new Vec3(1000, 3, 28),
                        r.halfExtent(),
                        0,
                        r.scattering(),
                        r.absorption(),
                        1,
                        1.5f,
                        .65f,
                        .45f,
                        2,
                        .2f);
        assertEquals(null, FogSunTable.layout(sky.withFog(List.of(r, distant))));
        var dense =
                new FogRegion(
                        r.center(),
                        r.halfExtent(),
                        0,
                        new Vec3(.2f, .2f, .2f),
                        r.absorption(),
                        4,
                        1.5f,
                        .65f,
                        .45f,
                        2,
                        .2f);
        assertEquals(null, FogSunTable.layout(sky.withFog(List.of(dense))));
    }

    @Test
    void rotatedColoredMediaStayWithinInterpolationTolerance() {
        var clock = new DayNightCycle();
        clock.hour(7);
        var r = RuinsMist.region(0);
        var random = new java.util.Random(71);
        for (float yaw : new float[] {-30, 30}) {
            var region =
                    new FogRegion(
                            r.center(),
                            r.halfExtent(),
                            yaw,
                            new Vec3(.07f, .02f, .04f),
                            new Vec3(.005f, .001f, .002f),
                            1,
                            2,
                            .8f,
                            1,
                            2,
                            .2f);
            var sky =
                    clock.atmosphericLighting(Atmosphere.Parameters.clearAir())
                            .sky()
                            .withFog(List.of(region));
            var layout = FogSunTable.layout(sky);
            assertTrue(layout != null);
            var table = new FogSunTable(layout);
            var scratch = new FogTransport.Scratch();
            float[] result = new float[3];
            int interpolated = 0;
            for (int index = 0; index < 3000; index++) {
                var p = layout.lower();
                var e = layout.extent();
                var d = layout.direction();
                scratch.ray.set(
                        p.x() + e.x() * random.nextFloat(),
                        p.y() + e.y() * random.nextFloat(),
                        p.z() + e.z() * random.nextFloat(),
                        d.x(),
                        d.y(),
                        d.z());
                if (table.sample(scratch.ray, result)) {
                    interpolated++;
                    FogTransport.transmission(sky.fog(), scratch.ray, scratch);
                    for (int c = 0; c < 3; c++) {
                        assertTrue(Math.abs(result[c] - scratch.sun[c]) <= .015);
                    }
                }
            }
            assertTrue(interpolated > 100);
        }
    }
}
