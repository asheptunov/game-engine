package engine;

import static harness.Assertions.*;

import engine.lights.PointLight;
import engine.objects.*;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

import scenes.viewport.*;

import java.util.List;

public class DielectricPathTest {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);
    private static final Material GLASS = new Material("glass", WHITE, Material.Kind.DIELECTRIC);

    private static ViewportState state(int size) {
        return new ViewportState(
                new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                size,
                size);
    }

    private static void close(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError(expected + " != " + actual);
    }

    private static void add(
            ViewportState st, String name, RenderPrimitive shape, Material material) {
        st.instances()
                .add(
                        new SceneInstance(
                                name,
                                PreparedGeometry.canonical(shape),
                                Transform.IDENTITY,
                                material));
    }

    private static void target(ViewportState st) {
        add(
                st,
                "target",
                new Rect(new Vec3(-20, -20, 8), new Vec3(0, 40, 0), new Vec3(40, 0, 0)),
                new Material("white", WHITE));
        st.addLight(new PointLight(new Vec3(0, 0, 7), WHITE, (float) Math.PI));
    }

    @Test
    void fresnelSnellAndTotalInternalReflection() {
        close(.04, Material.fresnel(1, 1.5f, 1), 1e-6);
        assertTrue(Material.fresnel(1, 1.5f, .1f) > .5f);
        close(0, Material.fresnel(1.5f, 1.5f, 0), 0);
        close(1, Material.fresnel(1.5f, 1, .5f), 0);
        var out = new Material.Sample();
        GLASS.sampleDielectric(.6f, 0, .8f, 0, 0, -1, 1, 1.5f, .9f, out);
        assertTrue(out.transmitted);
        close(.4, out.dx, 1e-6);
        close(Math.sqrt(.84), out.dz, 1e-6);
        close(4. / 9, out.red, 1e-6);
        close(1 - Material.fresnel(1, 1.5f, .8f), out.probability, 1e-6);
        GLASS.sampleDielectric(.6f, 0, .8f, 0, 0, -1, 1, 1.5f, 0, out);
        assertTrue(!out.transmitted);
        close(-.8, out.dz, 1e-6);
        close(1, out.red, 0);
        GLASS.sampleDielectric((float) Math.sqrt(.75), 0, .5f, 0, 0, -1, 1.5f, 1, .99f, out);
        assertTrue(!out.transmitted);
        close(1, out.probability, 0);
        GLASS.sampleDielectric(.6f, 0, .8f, 0, 0, -1, 1.5f, 1.5f, .5f, out);
        assertTrue(out.transmitted);
        close(.6, out.dx, 1e-6);
        close(.8, out.dz, 1e-6);
        close(1, out.red, 0);
        var rng = new DirectRgbTracer.Sampler();
        rng.reset(12, 0, 0);
        int reflections = 0;
        for (int i = 0; i < 50000; i++) {
            GLASS.sampleDielectric(0, 0, 1, 0, 0, -1, 1, 1.5f, rng.next(), out);
            if (!out.transmitted) reflections++;
            close(1, out.dx * out.dx + out.dy * out.dy + out.dz * out.dz, 1e-6);
        }
        close(.04, reflections / 50000., .003);
    }

    @Test
    void glassRequiresTwoContinuationsAndDoesNotUseBaseTint() {
        var st = state(1);
        target(st);
        st.pathDepth(2);
        add(st, "solid", new Sphere(new Vec3(0, 0, 4), 1), GLASS.withIor(1).withColor(Vec3.ZERO));
        var tracer = new DirectRgbTracer(st);
        var ray = new Ray(st.eye(), new Vec3(0, 0, 1));
        close(1, tracer.radiance(ray, 0)[0], 1e-5);
        assertEquals(2L, tracer.dielectricTransmissions);
        st.pathDepth(1);
        assertEquals(new float[] {0, 0, 0}, tracer.radiance(ray, 0));
        st.pathDepth(0);
        assertEquals(new float[] {0, 0, 0}, tracer.radiance(ray, 0));
    }

    @Test
    void distanceAbsorptionAndCameraInsideSphere() {
        var st = state(1);
        target(st);
        st.pathDepth(2);
        var absorption = new Vec3(.2f, .5f, 1);
        add(
                st,
                "solid",
                new Sphere(new Vec3(0, 0, 4), 1),
                GLASS.withIor(1).withAbsorption(absorption));
        var tracer = new DirectRgbTracer(st);
        var ray = new Ray(st.eye(), new Vec3(0, 0, 1));
        var rgb = tracer.radiance(ray, 0);
        close(Math.exp(-.2 * 2), rgb[0], .001);
        close(Math.exp(-.5 * 2), rgb[1], .001);
        close(Math.exp(-2), rgb[2], .001);
        st.instances()
                .set(
                        1,
                        new SceneInstance(
                                "solid",
                                new AnalyticSphere(new Vec3(0, 0, 4), 2),
                                Transform.IDENTITY,
                                GLASS.withIor(1).withAbsorption(absorption)));
        rgb = tracer.radiance(ray, 0);
        close(Math.exp(-.2 * 4), rgb[0], .001);
        close(Math.exp(-4), rgb[2], .001);
        st.pathDepth(1);
        rgb = tracer.radiance(new Ray(new Vec3(0, 0, 4), new Vec3(0, 0, 1)), 0);
        close(Math.exp(-.2 * 2), rgb[0], .001);
        // Starting inside glass carries the radiance IOR factor on the exit.
        var solid = st.instances().get(1);
        st.instances().set(1, solid.withMaterial(GLASS));
        boolean found = false;
        for (int sample = 0; sample < 100; sample++) {
            rgb = tracer.radiance(new Ray(new Vec3(0, 0, 4), new Vec3(0, 0, 1)), sample);
            if (rgb[0] > 0) {
                close(2.25, rgb[0], 1e-5);
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    void nestedMediaReplaceRatherThanAddAbsorptionAndRestoreOuterIor() {
        var st = state(1);
        target(st);
        st.pathDepth(4);
        add(
                st,
                "outer",
                new Sphere(new Vec3(0, 0, 4), 2),
                GLASS.withAbsorption(new Vec3(.2f, 0, 0)));
        add(
                st,
                "inner",
                new Sphere(new Vec3(0, 0, 4), 1),
                GLASS.withIor(1.33f).withAbsorption(new Vec3(0, .3f, 0)));
        var tracer = new DirectRgbTracer(st);
        var ray = new Ray(st.eye(), new Vec3(0, 0, 1));
        boolean found = false;
        for (int sample = 0; sample < 100; sample++) {
            var rgb = tracer.radiance(ray, sample);
            if (rgb[2] > 0) {
                close(Math.exp(-.4), rgb[0], .002);
                close(Math.exp(-.6), rgb[1], .002);
                close(1, rgb[2], 1e-5);
                found = true;
                break;
            }
        }
        assertTrue(found);
        st.pathDepth(2);
        found = false;
        // Inside both nested solids: inner -> outer -> air; net eta factor is 1.33^2.
        for (int sample = 0; sample < 100; sample++) {
            var rgb = tracer.radiance(new Ray(new Vec3(0, 0, 4), new Vec3(0, 0, 1)), sample);
            if (rgb[2] > 0) {
                close(1.33 * 1.33, rgb[2], 1e-5);
                close(Math.exp(-.2) * rgb[2], rgb[0], .002);
                close(Math.exp(-.3) * rgb[2], rgb[1], .002);
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    void transformedBoxAndEllipsoidExitTheirOwnBoundary() {
        for (boolean box : new boolean[] {false, true}) {
            var st = state(1);
            target(st);
            st.pathDepth(2);
            st.instances()
                    .add(
                            new SceneInstance(
                                    "solid",
                                    box ? SceneInstance.box() : new AnalyticSphere(Vec3.ZERO, 1),
                                    new Transform(
                                            new Vec3(0, 0, 4),
                                            new Vec3(0, 0, 20),
                                            new Vec3(1, 2, .5f)),
                                    GLASS.withIor(1).withAbsorption(new Vec3(.4f, 0, 0))));
            var tracer = new DirectRgbTracer(st);
            var rgb = tracer.radiance(new Ray(st.eye(), new Vec3(0, 0, 1)), 0);
            close(Math.exp(-.4), rgb[0], .001);
            close(1, rgb[1], 1e-5);
            var hit = tracer.intersect(new Ray(new Vec3(0, 0, 4), new Vec3(0, 0, 1)));
            assertTrue(!hit.frontFace);
        }
    }

    @Test
    void glassVisibilityDoesNotInventStraightThroughCaustics() {
        var st = state(1);
        target(st);
        st.pathDepth(0);
        add(st, "blocker", new Sphere(new Vec3(0, 0, 7.5f), .2f), GLASS.withIor(1));
        var tracer = new DirectRgbTracer(st);
        assertEquals(
                new float[] {0, 0, 0},
                tracer.radiance(new Ray(new Vec3(0, 0, 7.8f), new Vec3(0, 0, 1)), 0));
    }

    @Test
    void internalReflectionPreservesMediumAndTerminatesAtDepthLimit() {
        var st = state(1);
        st.pathDepth(32);
        add(st, "solid", new Sphere(Vec3.ZERO, 1), GLASS);
        var tracer = new DirectRgbTracer(st);
        assertEquals(
                new float[] {0, 0, 0},
                tracer.radiance(new Ray(new Vec3(.9f, 0, 0), new Vec3(0, 0, 1)), 0));
        assertEquals(32L, tracer.dielectricReflections);
        assertEquals(0L, tracer.dielectricTransmissions);
        assertEquals(33L, tracer.absorptionSegments);
    }

    @Test
    void directLightSegmentInsideMediumAlsoAbsorbs() {
        var st = state(1);
        st.pathDepth(0);
        add(
                st,
                "glass",
                new Sphere(Vec3.ZERO, 10),
                GLASS.withIor(1).withAbsorption(new Vec3(.2f, 0, 0)));
        add(
                st,
                "target",
                new Rect(new Vec3(-2, -2, 2), new Vec3(0, 4, 0), new Vec3(4, 0, 0)),
                new Material("white", WHITE));
        st.addLight(new PointLight(Vec3.ZERO, WHITE, (float) (4 * Math.PI)));
        var rgb = new DirectRgbTracer(st).radiance(new Ray(Vec3.ZERO, new Vec3(0, 0, 1)), 0);
        close(Math.exp(-.8), rgb[0], 1e-5);
        close(1, rgb[1], 1e-5);
    }

    private static float[][][] copy(float[][][] source) {
        var out = new float[3][][];
        for (int c = 0; c < 3; c++) {
            out[c] = new float[source[c].length][];
            for (int y = 0; y < out[c].length; y++) out[c][y] = source[c][y].clone();
        }
        return out;
    }

    private static double error(float[][][] actual, float[][][] reference) {
        double result = 0;
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < actual[c].length; y++)
                for (int x = 0; x < actual[c][y].length; x++) {
                    double d = actual[c][y][x] - reference[c][y][x];
                    result += d * d;
                }
        return result;
    }

    @Test
    void seededGlassConvergesAndBatchSizesAgree() {
        var a = state(64);
        var b = state(64);
        ScenePresets.load(a, "glass");
        ScenePresets.load(b, "glass");
        a.samplesPerFrame(1);
        b.samplesPerFrame(8);
        a.sampleTarget(1);
        b.sampleTarget(64);
        var ta = new DirectRgbTracer(a);
        var tb = new DirectRgbTracer(b);
        var first = copy(ta.trace());
        a.sampleTarget(64);
        while (a.accumulatedSamples() < 64) ta.trace();
        while (b.accumulatedSamples() < 64) tb.trace();
        var converged = copy(ta.trace());
        assertEquals(converged, tb.trace());
        a.sampleTarget(512);
        while (a.accumulatedSamples() < 512) ta.trace();
        double e1 = error(first, ta.trace()), e64 = error(converged, ta.trace());
        assertTrue(e64 < e1 * .15);
        System.out.printf("Glass squared error: 1 spp %.6f; 64 spp %.6f%n", e1, e64);
    }

    @Test
    void controlsPreserveParametersInvalidateAndRejectOpenGlassAtomically() {
        var st = state(64);
        ScenePresets.load(st, "glass");
        var cmd = new ViewportCommand(st);
        assertTrue(cmd.run("view", "select", "glass-sphere").isSuccess());
        assertTrue(cmd.run("view", "ior", "1.33").isSuccess());
        assertTrue(cmd.run("view", "absorption", ".1", ".2", ".3").isSuccess());
        assertTrue(cmd.run("view", "color", "000000").isSuccess());
        close(1.33, st.instances().getFirst().material().ior(), 1e-6);
        assertEquals(new Vec3(.1f, .2f, .3f), st.instances().getFirst().material().absorption());
        var tracer = new DirectRgbTracer(st);
        tracer.trace();
        tracer.trace();
        assertEquals(2L, st.accumulatedSamples());
        cmd.run("view", "ior", "1.4");
        tracer.trace();
        assertEquals(1L, st.accumulatedSamples());
        tracer.trace();
        cmd.run("view", "absorption", "0", "0", "0");
        tracer.trace();
        assertEquals(1L, st.accumulatedSamples());
        assertTrue(cmd.run("view", "ior", "0.9").isFailure());
        assertTrue(cmd.run("view", "ior", "NaN").isFailure());
        assertTrue(cmd.run("view", "absorption", "-1", "0", "0").isFailure());
        cmd.run("view", "select", "floor");
        assertTrue(cmd.run("view", "type", "dielectric").isFailure());
        // Shared material used by a sphere and open floor: failed edit must not change either.
        var floor = st.instances().getLast();
        var sphere = st.instances().getFirst();
        st.instances().set(0, sphere.withMaterial(floor.material()));
        var before = List.copyOf(st.instances());
        cmd.run("view", "select", "glass-sphere");
        assertTrue(cmd.run("view", "type", "dielectric").isFailure());
        assertEquals(before, st.instances());
        ScenePresets.load(st, "glass-inside");
        assertEquals(new Vec3(-1.15f, 0, 5), st.eye());
        assertTrue(tracer.trace()[0][32][32] > 0);
        assertTrue(tracer.profile.dielectricTransmissions() > 0);
    }

    private static void image(float[][][] rgb, String filename) throws Exception {
        int size = rgb[0].length;
        var image =
                new java.awt.image.BufferedImage(
                        size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int color = 0;
                for (int c = 0; c < 3; c++)
                    color =
                            (color << 8)
                                    | Byte.toUnsignedInt(
                                            DisplayMapping.encode(rgb[c][size - 1 - y][x], 1));
                image.setRGB(x, y, color);
            }
        javax.imageio.ImageIO.write(image, "png", new java.io.File("out/cli/" + filename));
    }

    @Test
    void glassPreviewConvergesAndIorChangesImage() throws Exception {
        var st = state(240);
        ScenePresets.load(st, "glass");
        st.samplesPerFrame(8);
        st.sampleTarget(64);
        var tracer = new DirectRgbTracer(st);
        while (st.accumulatedSamples() < 64) tracer.trace();
        image(tracer.trace(), "glass-preview-64.png");
        var old = tracer.trace();
        double[][][] reference = new double[3][240][240];
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < 240; y++)
                for (int x = 0; x < 240; x++) reference[c][y][x] = old[c][y][x];
        st.instances()
                .replaceAll(
                        o ->
                                o.material().kind() == Material.Kind.DIELECTRIC
                                        ? o.withMaterial(o.material().withIor(1))
                                        : o);
        do {
            tracer.trace();
        } while (st.accumulatedSamples() < 64);
        double difference = 0;
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < 240; y++)
                for (int x = 0; x < 240; x++)
                    difference += Math.abs(reference[c][y][x] - old[c][y][x]);
        assertTrue(difference > 100);
        image(tracer.trace(), "glass-ior-1-preview.png");
        ScenePresets.load(st, "glass-inside");
        st.sampleTarget(64);
        do {
            tracer.trace();
        } while (st.accumulatedSamples() < 64);
        image(tracer.trace(), "glass-inside-preview-64.png");
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
