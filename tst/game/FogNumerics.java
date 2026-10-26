package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.FogRegion;
import engine.FogTransport;
import engine.Sky;
import engine.lights.DirectionalLight;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.List;

public final class FogNumerics {
    private static final StringBuilder MEASUREMENTS =
            new StringBuilder("expected,actual,absoluteError,tolerance\n");

    public static void main(String[] args) throws Exception {
        SuiteRunner.runThis();
        if (args.length > 0) {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(args[0], "fog-numerics.csv"), MEASUREMENTS);
        }
    }

    private static FogRegion uniform(Vec3 scattering, Vec3 absorption) {
        return new FogRegion(
                Vec3.ZERO, new Vec3(2, 2, 2), 0, scattering, absorption, 1, 0, 0, 0, 0, 0);
    }

    private static FogTransport.Scratch integrate(
            List<FogRegion> regions, float originZ, int steps, boolean visible) {
        var scratch = new FogTransport.Scratch(steps, steps);
        var light = new DirectionalLight(new Vec3(1, 0, 0), new Vec3(1, 1, 1), 2);
        FogTransport.segment(
                Sky.BLACK.withFog(regions),
                new DirectionalLight[] {light},
                scratch.ray.set(0, 0, originZ, 0, 0, 1),
                20,
                false,
                (x, y, z, dx, dy, dz) -> visible,
                scratch);
        return scratch;
    }

    @Test
    void homogeneousBeerAndSingleScatteringBrightness() {
        var region = uniform(new Vec3(.03f, .04f, .05f), new Vec3(.01f, .02f, .03f));
        for (float origin : new float[] {-4, 0, 3}) {
            var result = integrate(List.of(region), origin, 24, true);
            double length = origin < 0 ? 4 : origin == 0 ? 2 : 0;
            for (int c = 0; c < 3; c++) {
                double scattering = .03 + c * .01;
                double k = .04 + c * .02;
                double transmission = Math.exp(-k * length);
                double light =
                        2 * Math.exp(-k * 2) * scattering / (4 * Math.PI) * (1 - transmission) / k;
                near(transmission, result.transmission[c], 2e-6);
                near(light, result.radiance[c], 2e-6);
            }
        }
    }

    @Test
    void overlapsAddCoefficientsAndKeepIndividualPhaseFunctions() {
        var a = uniform(new Vec3(.03f, .04f, .05f), Vec3.ZERO);
        var b = uniform(Vec3.ZERO, new Vec3(.01f, .02f, .03f));
        var combined = uniform(a.scattering(), b.absorption());
        var first = integrate(List.of(a, b), -4, 24, true);
        var second = integrate(List.of(b, a), -4, 24, true);
        var expected = integrate(List.of(combined), -4, 24, true);
        assertEquals(first.radiance, second.radiance);
        for (int c = 0; c < 3; c++) {
            near(expected.radiance[c], first.radiance[c], 1e-6);
            near(expected.transmission[c], first.transmission[c], 1e-6);
        }
        var shadow = integrate(List.of(a, b), -4, 24, false);
        assertEquals(new float[3], shadow.radiance);
        assertEquals(first.transmission, shadow.transmission);
    }

    @Test
    void zeroDensityIdentityFiniteValidationAndTransforms() {
        var empty = uniform(Vec3.ZERO, Vec3.ZERO);
        assertEquals(Sky.BLACK, Sky.BLACK.withFog(List.of(empty)));
        var r = RuinsMist.region(0);
        var moved =
                new FogRegion(
                        new Vec3(100, 20, -40),
                        new Vec3(26, 10, 24),
                        90,
                        r.scattering(),
                        r.absorption(),
                        r.density(),
                        r.heightFalloff(),
                        r.anisotropy(),
                        r.variation(),
                        r.frequency(),
                        r.feather());
        near(r.sample(22, 4, 31), moved.sample(106, 22, -44), 1e-6);
        boolean rejected = false;
        try {
            new FogRegion(
                    Vec3.ZERO,
                    new Vec3(2, 2, 2),
                    0,
                    Vec3.ZERO,
                    Vec3.ZERO,
                    Float.NaN,
                    0,
                    0,
                    0,
                    0,
                    0);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void spatialDensityConvergesAgainstRefinedIntegration() {
        var r =
                new FogRegion(
                        Vec3.ZERO,
                        new Vec3(2, 2, 2),
                        25,
                        new Vec3(.04f, .05f, .06f),
                        new Vec3(.01f, .02f, .03f),
                        1,
                        2,
                        .6f,
                        .65f,
                        3,
                        .2f);
        var production = integrate(List.of(r), -4, 24, true);
        var reference = integrate(List.of(r), -4, 192, true);
        var finer = integrate(List.of(r), -4, 384, true);
        for (int c = 0; c < 3; c++) {
            near(reference.transmission[c], production.transmission[c], .003);
            near(reference.radiance[c], production.radiance[c], .0002);
            near(reference.radiance[c], finer.radiance[c], .00001);
        }
    }

    @Test
    void overlappingPhaseFunctionsAreSummedAndBoundaryExitIsContinuous() {
        var first =
                new FogRegion(
                        Vec3.ZERO,
                        new Vec3(2, 2, 2),
                        0,
                        new Vec3(.03f, .03f, .03f),
                        Vec3.ZERO,
                        1,
                        0,
                        .6f,
                        0,
                        0,
                        0);
        var second =
                new FogRegion(
                        Vec3.ZERO,
                        new Vec3(2, 2, 2),
                        0,
                        new Vec3(.02f, .02f, .02f),
                        Vec3.ZERO,
                        1,
                        0,
                        -.4f,
                        0,
                        0,
                        0);
        var actual = integrate(List.of(first, second), -4, 24, true);
        double source = 2 * Math.exp(-.05 * 2) * (.03 * phase(.6) + .02 * phase(-.4));
        near(source * (1 - Math.exp(-.05 * 4)) / .05, actual.radiance[0], 2e-6);
        for (float origin : new float[] {1.99f, 2, 2.01f}) {
            var scratch = new FogTransport.Scratch();
            FogTransport.segment(
                    Sky.BLACK.withFog(List.of(first)),
                    new DirectionalLight[0],
                    scratch.ray.set(0, 0, origin, 0, 0, 1),
                    20,
                    false,
                    (x, y, z, dx, dy, dz) -> true,
                    scratch);
            near(Math.exp(-.03 * Math.max(0, 2 - origin)), scratch.transmission[0], 2e-6);
        }
    }

    private static double phase(double g) {
        return (1 - g * g) / (4 * Math.PI * Math.pow(1 + g * g, 1.5));
    }

    private static void near(double expected, double actual, double tolerance) {
        MEASUREMENTS
                .append(expected)
                .append(',')
                .append(actual)
                .append(',')
                .append(Math.abs(actual - expected))
                .append(',')
                .append(tolerance)
                .append('\n');
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(
                    "expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
        }
    }
}
