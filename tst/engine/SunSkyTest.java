package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.lights.DirectionalLight;
import engine.lights.PointLight;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

import java.util.List;

public final class SunSkyTest {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);
    private static final Material DIFFUSE = new Material("diffuse", WHITE);

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static ViewportState state() {
        var state =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, -2), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        64,
                        64);
        state.camera(new Camera(new Vec3(0, 0, -3), state.cameraSensor()));
        return state;
    }

    private static SceneInstance cube(String name, Vec3 position, Material material) {
        return new SceneInstance(
                name,
                BoxGeometry.UNIT,
                new Transform(position, Vec3.ZERO, new Vec3(1, 1, 1)),
                material);
    }

    private static void near(double expected, double actual, double tolerance) {
        assertTrue(Math.abs(expected - actual) < tolerance);
    }

    private static float[] radiance(ViewportState state, Vec3 eye, Vec3 direction) {
        try (var tracer = new DirectRgbTracer(state)) {
            return tracer.radiance(new Ray(eye, direction), 0);
        }
    }

    @Test
    void sunDirectionPointsTowardSourceAndIlluminationIsDistanceIndependent() {
        var state = state();
        state.instances().add(cube("near", Vec3.ZERO, DIFFUSE));
        state.instances().add(cube("far", new Vec3(100, 0, 50), DIFFUSE));
        var sun = new DirectionalLight(new Vec3(0, 0, -7), WHITE, 3);
        assertEquals(new Vec3(0, 0, -1), sun.direction());
        state.lights().add(sun);
        var near = radiance(state, new Vec3(0, 0, -3), new Vec3(0, 0, 1));
        var far = radiance(state, new Vec3(100, 0, 47), new Vec3(0, 0, 1));
        near(3 / Math.PI, near[0], 2e-5);
        assertEquals(near, far);
        assertEquals(near, radiance(state, new Vec3(0, 0, -1.5f), new Vec3(0, 0, 1)));
        state.lights().clear();
        state.lights().add(new DirectionalLight(new Vec3(0, 0, 1), WHITE, 3));
        near(0, radiance(state, new Vec3(0, 0, -3), new Vec3(0, 0, 1))[0], 2e-5);
    }

    @Test
    void sunShadowsReachDistantOccludersAndPointLightsStillContribute() {
        var state = state();
        state.instances().add(cube("surface", Vec3.ZERO, DIFFUSE));
        state.lights().add(new DirectionalLight(new Vec3(0, 0, -1), WHITE, 3));
        state.instances().add(cube("distant blocker", new Vec3(0, 0, -10000), DIFFUSE));
        near(0, radiance(state, new Vec3(0, 0, -3), new Vec3(0, 0, 1))[0], 2e-5);
        state.instances().removeLast();
        state.lights().add(new PointLight(new Vec3(0, 0, -3), WHITE, 12));
        near(6 / Math.PI, radiance(state, new Vec3(0, 0, -3), new Vec3(0, 0, 1))[0], 2e-5);
        var texture = new Texture2D(1, 1, new int[] {0xffff0000});
        state.instances()
                .set(
                        0,
                        cube(
                                "surface",
                                Vec3.ZERO,
                                DIFFUSE.withTextures(new CubeTextures(texture, texture, texture))));
        var red = radiance(state, new Vec3(0, 0, -3), new Vec3(0, 0, 1));
        near(6 / Math.PI, red[0], 2e-5);
        near(0, red[1], 2e-5);
    }

    @Test
    void skyGradientUsesWorldDirectionAndLegacyWorldDefaultsToBlack() {
        var state = state();
        var sky = new Sky(new Vec3(.2f, .4f, .6f), new Vec3(.8f, 1, 1.2f));
        state.sky(sky);
        assertEquals(new float[] {.8f, 1, 1.2f}, radiance(state, Vec3.ZERO, new Vec3(0, 1, 0)));
        assertEquals(new float[] {.2f, .4f, .6f}, radiance(state, Vec3.ZERO, new Vec3(0, -1, 0)));
        var horizon = radiance(state, Vec3.ZERO, new Vec3(1, 0, 0));
        near(.5, horizon[0], 2e-5);
        near(.7, horizon[1], 2e-5);
        near(.9, horizon[2], 2e-5);
        assertEquals(sky, state.renderSnapshot().sky());
        assertEquals(Sky.BLACK, WorldSnapshot.of(List.of(), List.of()).sky());
        assertEquals(new float[3], radiance(state(), Vec3.ZERO, new Vec3(0, 1, 0)));
    }

    @Test
    void isolatedDiffuseConstantSkyMatchesReflectanceAndGradientConverges() {
        var state = state();
        var reflectance = new Vec3(.25f, .5f, .75f);
        state.instances().add(cube("diffuse", Vec3.ZERO, new Material("colored", reflectance)));
        state.pathDepth(1);
        state.sky(new Sky(new Vec3(.4f, .6f, .8f), new Vec3(.4f, .6f, .8f)));
        var ray = new Ray(new Vec3(0, 3, 0), new Vec3(0, -1, 0));
        try (var tracer = new DirectRgbTracer(state)) {
            // Cosine-weighted Lambert sampling cancels the PDF: every unoccluded constant-sky
            // sample equals reflectance * radiance. Numerical tolerance is 2e-5 per channel.
            var constant = tracer.radiance(ray, 0);
            near(.1, constant[0], 2e-5);
            near(.3, constant[1], 2e-5);
            near(.6, constant[2], 2e-5);
            state.sky(new Sky(Vec3.ZERO, WHITE));
            double sum = 0;
            for (int sample = 0; sample < 4096; sample++) {
                sum += tracer.radiance(ray, sample)[0];
            }
            // Mean cosine under cosine-weighted hemisphere sampling is 2/3. The gradient
            // averages 5/6. Absolute tolerance .003 covers seeded Monte Carlo uncertainty.
            near(.25 * 5 / 6, sum / 4096, .003);
            state.sky(Sky.BLACK);
            assertEquals(new float[3], tracer.radiance(ray, 0));
        }
    }

    @Test
    void invalidLightsSkyAndAdvancedTransportRejectAtWorldBoundary() {
        reject(() -> new DirectionalLight(Vec3.ZERO, WHITE, 1));
        reject(() -> new DirectionalLight(new Vec3(Float.NaN, 1, 0), WHITE, 1));
        reject(() -> new DirectionalLight(WHITE, WHITE, -1));
        reject(() -> new Sky(Vec3.ZERO, new Vec3(-1, 0, 0)));
        reject(() -> new Sky(Vec3.ZERO, new Vec3(Float.POSITIVE_INFINITY, 0, 0)));
        var sun = new DirectionalLight(WHITE, WHITE, 1);
        var sky = new Sky(WHITE, WHITE);
        var materials =
                List.of(
                        DIFFUSE.withKind(Material.Kind.MIRROR),
                        DIFFUSE.withKind(Material.Kind.DIELECTRIC),
                        DIFFUSE.withKind(Material.Kind.DIELECTRIC).withScattering(1));
        for (var material : materials) {
            var instances = List.of(cube("unsupported", Vec3.ZERO, material));
            reject(() -> new WorldSnapshot(0, instances, List.of(), List.of(sun)));
            reject(() -> new WorldSnapshot(0, instances, List.of(), List.of(), sky));
            // Old black-sky point-light worlds retain advanced transport.
            WorldSnapshot.of(instances, List.of(new PointLight(new Vec3(0, 4, 0))));
        }
    }

    private static void reject(Runnable operation) {
        boolean rejected = false;
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void lightingEditsInvalidateIdentityAndUndoNeverRevivesPublications() throws Exception {
        var state = state();
        var key = state.renderKey();
        state.sky(new Sky(WHITE, WHITE));
        assertTrue(!key.sameTransport(state.renderKey()));
        assertTrue(!key.equals(state.renderKey()));
        var view = new RenderView(state.camera(), 64, 64);
        var white = new WorldSnapshot(0, List.of(), List.of(), List.of(), state.sky());
        var black = WorldSnapshot.of(List.of(), List.of());
        var settings = RenderSettings.defaults().withTemporal(true, 1);
        try (var session = RenderEngine.openSession(white, view, settings)) {
            var first = await(session);
            try (first) {
                near(1, first.rawPixels().value(0, 32, 32), 2e-5);
                session.update(black, view, settings);
                long blackGeneration;
                try (var next = await(session)) {
                    blackGeneration = next.generation();
                    assertTrue(blackGeneration > first.generation());
                    near(0, next.rawPixels().value(0, 32, 32), 2e-5);
                }
                session.update(white, view, settings);
                try (var next = await(session)) {
                    assertTrue(next.generation() > blackGeneration);
                    near(1, next.rawPixels().value(0, 32, 32), 2e-5);
                }
                near(1, first.rawPixels().value(0, 32, 32), 2e-5);
            }
        }
        var instances = List.of(cube("cube", Vec3.ZERO, DIFFUSE));
        var front =
                WorldSnapshot.of(
                        instances, List.of(new DirectionalLight(new Vec3(0, 0, -1), WHITE, 3)));
        var back =
                WorldSnapshot.of(
                        instances, List.of(new DirectionalLight(new Vec3(0, 0, 1), WHITE, 3)));
        try (var session = RenderEngine.openSession(front, view, RenderSettings.defaults())) {
            long generation;
            try (var image = await(session)) {
                generation = image.generation();
                near(3 / Math.PI, image.rawPixels().value(0, 32, 32), 2e-5);
            }
            session.update(back, view, RenderSettings.defaults());
            try (var image = await(session)) {
                assertTrue(image.generation() > generation);
                near(0, image.rawPixels().value(0, 32, 32), 2e-5);
                generation = image.generation();
            }
            session.update(front, view, RenderSettings.defaults());
            try (var image = await(session)) {
                assertTrue(image.generation() > generation);
            }
        }
    }

    private static RenderImage await(RenderSession session) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (image.generation() == session.progress(image).requestedGeneration()) {
                    return image;
                }
                image.close();
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No current image");
    }

    @Test
    void seededSunSkyImagesAgreeAcrossWorkersAndBatchBoundaries() {
        var single = state();
        single.instances().add(cube("cube", Vec3.ZERO, DIFFUSE));
        single.lights().add(new DirectionalLight(new Vec3(-1, 1, -1), WHITE, 3));
        single.sky(new Sky(new Vec3(.2f, .3f, .5f), new Vec3(.4f, .5f, 1)));
        single.pathDepth(2);
        single.workers(1);
        single.samplesPerFrame(8);
        single.sampleTarget(8);
        var parallel = single.renderSnapshot();
        parallel.workers(Math.min(2, Runtime.getRuntime().availableProcessors()));
        parallel.samplesPerFrame(1);
        try (var a = new DirectRgbTracer(single);
                var b = new DirectRgbTracer(parallel)) {
            var expected = a.trace();
            for (int pass = 0; pass < 8; pass++) {
                b.trace();
            }
            var actual = b.trace();
            for (int channel = 0; channel < 3; channel++) {
                for (int row = 0; row < 64; row++) {
                    assertEquals(expected[channel][row], actual[channel][row]);
                }
            }
        }
    }
}
