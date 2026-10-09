package engine;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertSame;
import static harness.Assertions.assertTrue;

import engine.lights.PointLight;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CubeTextureTest {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static Texture2D solid(int rgb) {
        return new Texture2D(1, 1, new int[] {0xff000000 | rgb});
    }

    private static CubeTextures binding(Texture2D texture) {
        return new CubeTextures(texture, texture, texture);
    }

    private static void near(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 2e-5);
    }

    @Test
    void ownsPixelsConvertsSrgbAndClampsNearestTexel() {
        var pixels = new int[] {0xff808080, 0xffff0000, 0xff00ff00, 0xff0000ff};
        var texture = new Texture2D(2, 2, pixels);
        pixels[0] = 0;
        var result = new float[3];
        texture.sample(0, 0, result);
        near(.2158605, result[0]);
        texture.sample(.499f, .499f, result);
        near(.2158605, result[1]);
        texture.sample(.5f, 0, result);
        assertEquals(new float[] {1, 0, 0}, result);
        texture.sample(1, 1, result);
        assertEquals(new float[] {0, 0, 1}, result);
        texture.sample(-.1f, 2, result);
        assertEquals(new float[] {0, 1, 0}, result);
        boolean rejected = false;
        try {
            new Texture2D(1, 1, new int[] {0x80ffffff});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertEquals(
                texture,
                new Texture2D(2, 2, new int[] {0xff808080, 0xffff0000, 0xff00ff00, 0xff0000ff}));
    }

    @Test
    void allFaceOrientationsAndEdgeNormalsChooseExpectedTexels() {
        var pattern =
                new Texture2D(2, 2, new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff});
        var textures = binding(pattern);
        var result = new float[3];
        // All six upper-left image corners, seen from outside.
        float[][] corners = {
            {-1, 1, -1, 0, 0, -1}, {1, 1, 1, 0, 0, 1},
            {1, 1, 1, 1, 0, 0}, {-1, 1, -1, -1, 0, 0},
            {-1, 1, -1, 0, 1, 0}, {-1, -1, 1, 0, -1, 0}
        };
        for (var corner : corners) {
            textures.sampleLocal(
                    corner[0], corner[1], corner[2], corner[3], corner[4], corner[5], result);
            assertEquals(new float[] {1, 0, 0}, result);
        }
        var faces = new CubeTextures(solid(0xff0000), solid(0x0000ff), solid(0x00ff00));
        faces.sampleLocal(1, 1, 1, 0, 1, 0, result);
        assertEquals(new float[] {1, 0, 0}, result);
        faces.sampleLocal(1, 1, 1, 1, 0, 0, result);
        assertEquals(new float[] {0, 1, 0}, result);
        faces.sampleLocal(0, -1, 0, 0, -1, 0, result);
        assertEquals(new float[] {0, 0, 1}, result);
    }

    private static ViewportState state() {
        return new ViewportState(
                new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)), 64, 64);
    }

    private static float[] direct(Material material, Transform transform, Vec3 localPoint) {
        var state = state();
        state.instances().add(new SceneInstance("cube", BoxGeometry.UNIT, transform, material));
        var normal = transform.normal(new Vec3(0, 0, -1));
        var point = transform.point(localPoint);
        state.lights().add(new PointLight(point.add(normal.scale(2)), WHITE, 12));
        try (var tracer = new DirectRgbTracer(state)) {
            return tracer.radiance(new Ray(point.add(normal.scale(3)), normal.scale(-1)), 0);
        }
    }

    @Test
    void textureModulatesDirectLightAndStaysAttachedUnderTrs() {
        var pattern =
                new Texture2D(2, 2, new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff});
        var material = new Material("pattern", WHITE).withTextures(binding(pattern));
        var point = new Vec3(-.5f, .5f, -1);
        var flat = direct(material, Transform.IDENTITY, point);
        var moved =
                direct(
                        material,
                        new Transform(
                                new Vec3(7, -2, 4), new Vec3(25, 55, 12), new Vec3(.6f, 1.2f, .4f)),
                        point);
        near(3 / Math.PI, flat[0]);
        near(flat[0], moved[0]);
        near(0, moved[1]);
        near(0, moved[2]);
        var tinted = direct(material.withColor(new Vec3(.5f, 1, 1)), Transform.IDENTITY, point);
        near(flat[0] * .5, tinted[0]);
    }

    private static float[] bounced(CubeTextures textures, int samples, int depth, float span) {
        var state = state();
        state.pathDepth(depth);
        state.instances()
                .add(
                        new SceneInstance(
                                "cube",
                                BoxGeometry.UNIT,
                                Transform.IDENTITY,
                                new Material("cube", WHITE).withTextures(textures)));
        // Large one-sided emissive plane behind the camera; every sampled front hemisphere hits it.
        state.instances()
                .add(
                        new SceneInstance(
                                "emitter",
                                PolygonMesh.parallelogram(
                                        new Vec3(-span, -span, -4),
                                        new Vec3(span * 2, 0, 0),
                                        new Vec3(0, span * 2, 0)),
                                Transform.IDENTITY,
                                new Material("emitter", WHITE).withEmission(WHITE)));
        var sum = new float[3];
        try (var tracer = new DirectRgbTracer(state)) {
            for (int sample = 0; sample < samples; sample++) {
                var value = tracer.radiance(new Ray(new Vec3(0, 0, -3), new Vec3(0, 0, 1)), sample);
                for (int channel = 0; channel < 3; channel++) {
                    sum[channel] += value[channel] / samples;
                }
            }
        }
        return sum;
    }

    @Test
    void diffuseContinuationAndAreaLightingUseTextureReflectance() {
        var white = bounced(null, 128, 1, 1000);
        var red = bounced(binding(solid(0xff0000)), 128, 1, 1000);
        near(white[0], red[0]);
        near(0, red[1]);
        near(0, red[2]);
        // Emitter's tiny solid-angle density makes its direct samples negligible; continuation
        // dominates.
        assertTrue(red[0] > .95f && red[0] < 1.05f);
    }

    @Test
    void terminalAreaLightingUsesTextureWithoutAContinuation() {
        var white = bounced(null, 128, 0, 2);
        var red = bounced(binding(solid(0xff0000)), 128, 0, 2);
        assertTrue(white[0] > .1);
        near(white[0], red[0]);
        near(0, red[1]);
        near(0, red[2]);
    }

    @Test
    void materialCopiesRetainBindingsAndUnsupportedKindsGeometryAndSaveReject() throws Exception {
        var textures = binding(solid(0x804020));
        var material = new Material("cube", WHITE).withTextures(textures);
        assertSame(
                textures,
                material.withColor(WHITE)
                        .withIor(1.4f)
                        .withAbsorption(Vec3.ZERO)
                        .withRoughness(.3f)
                        .withEmission(Vec3.ZERO)
                        .withScattering(0)
                        .withAnisotropy(.1f)
                        .withKind(Material.Kind.DIFFUSE)
                        .textures());
        boolean rejected = false;
        try {
            material.withKind(Material.Kind.MIRROR);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        rejected = false;
        try {
            new SceneInstance(
                    "sphere", new AnalyticSphere(Vec3.ZERO, 1), Transform.IDENTITY, material);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        var document = new SceneDocument();
        document.transact(
                edit -> {
                    var geometry = edit.createGeometry("cube", BoxGeometry.UNIT);
                    var asset = edit.createMaterial("textured cube", material);
                    edit.assignGeometry(
                            edit.createNode("cube", null, Transform.IDENTITY), geometry, asset);
                });
        assertSame(textures, document.snapshot().materialAssets().getFirst().material().textures());
        assertSame(
                textures,
                document.snapshot().toWorldSnapshot().instances().getFirst().material().textures());
        var path = Path.of("out/game-check/preserved.scene.xml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "existing scene must survive");
        rejected = false;
        try {
            SceneFiles.save(path, document.snapshot());
        } catch (java.io.IOException expected) {
            rejected = expected.getMessage().contains("textured cube");
        }
        assertTrue(rejected);
        assertEquals("existing scene must survive", Files.readString(path));
    }

    @Test
    void textureEditsInvalidateSamplesEvenWhenReturningToAnEarlierBinding() throws Exception {
        var material = new Material("cube", WHITE);
        var world =
                WorldSnapshot.of(
                        List.of(
                                new SceneInstance(
                                        "cube",
                                        BoxGeometry.UNIT,
                                        Transform.IDENTITY,
                                        material.withTextures(binding(solid(0xff0000))))),
                        List.of(new PointLight(new Vec3(0, 0, -3), WHITE, 12)));
        var camera =
                new Camera(
                        new Vec3(0, 0, -3),
                        new Rect(
                                new Vec3(-.4f, -.4f, -2),
                                new Vec3(.8f, 0, 0),
                                new Vec3(0, .8f, 0)));
        var view = new RenderView(camera, 64, 64);
        var settings = RenderSettings.defaults();
        try (var session = RenderEngine.openSession(world, view, settings)) {
            long redGeneration = await(session);
            var blue =
                    WorldSnapshot.of(
                            List.of(
                                    new SceneInstance(
                                            "cube",
                                            BoxGeometry.UNIT,
                                            Transform.IDENTITY,
                                            material.withTextures(binding(solid(0x0000ff))))),
                            world.lights());
            session.update(blue, view, settings);
            long blueGeneration = await(session);
            assertTrue(blueGeneration > redGeneration);
            session.update(world, view, settings);
            assertTrue(await(session) > blueGeneration);
        }
    }

    private static long await(RenderSession session) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            try (var image = session.acquireImage()) {
                if (image != null
                        && image.generation() == session.progress(image).requestedGeneration()) {
                    return image.generation();
                }
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No current image");
    }
}
