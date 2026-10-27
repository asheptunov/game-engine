package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertSame;
import static harness.Assertions.assertTrue;

import engine.Camera;
import engine.PolygonMesh;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.RenderView;
import engine.Transform;
import engine.WorldSnapshot;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

public final class RifleMeshTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void asynchronousRiflePublishesOwnedImageAndCloses() throws Exception {
        var camera = new GameNavigation().view().camera();
        try (var rifle = new RifleView()) {
            long start = System.nanoTime();
            var image = rifle.request(320, 200, 0, camera, GameLighting.defaults(), 1);
            assertTrue((System.nanoTime() - start) < 200_000_000L);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (image == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
                image = rifle.request(320, 200, 0, camera, GameLighting.defaults(), 1);
            }
            assertEquals(null, rifle.error());
            assertTrue(image != null);
            var before = image.getRGB(0, 0, 320, 200, null, 0, 320);
            rifle.request(320, 200, .625, camera, GameLighting.defaults(), 1);
            rifle.close();
            assertEquals(before, image.getRGB(0, 0, 320, 200, null, 0, 320));
        }
    }

    @Test
    void sharesGeometryAndMovesOnlyBolt() {
        var resting = RifleMesh.instances("one/", Transform.IDENTITY, 0);
        var cycling = RifleMesh.instances("two/", Transform.IDENTITY, .625);
        int faces = 0;
        for (int i = 0; i < resting.size(); i++) {
            var a = resting.get(i);
            var b = cycling.get(i);
            assertSame(a.geometry(), b.geometry());
            faces += ((PolygonMesh) a.geometry()).faces().size();
            if (a.name().contains("bolt/")) {
                assertTrue(b.transform().position.z() < a.transform().position.z());
            } else {
                assertEquals(a.transform(), b.transform());
            }
        }
        assertTrue(faces > 500);
        assertTrue(resting.size() <= 8);
    }

    @Test
    void viewCachesMeshPassAndPreservesTransparentBackground() throws Exception {
        var renderer = new RifleView();
        var rest = renderer.image(960, 600, 0);
        assertSame(rest, renderer.image(960, 600, 0));
        assertEquals(0, rest.getRGB(480, 200));
        int visible = 0;
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 960; x++) {
                if ((rest.getRGB(x, y) >>> 24) != 0) {
                    visible++;
                }
            }
        }
        assertTrue(visible > 5000 && visible < 200000);
        var cycle = renderer.image(960, 600, .625);
        assertSame(rest, renderer.image(960, 600, 0));
        assertSame(cycle, renderer.image(960, 600, .625));
        assertTrue(
                !java.util.Arrays.equals(
                        rest.getRGB(0, 0, 960, 600, null, 0, 960),
                        cycle.getRGB(0, 0, 960, 600, null, 0, 960)));
        Files.createDirectories(Path.of("out/game"));
        ImageIO.write(rest, "png", Path.of("out/game/rifle-first-person.png").toFile());
        ImageIO.write(cycle, "png", Path.of("out/game/rifle-bolt.png").toFile());
    }

    @Test
    void rayTracesSharedMeshPreview() throws Exception {
        var eye = new Vec3(1.15f, .55f, -1.1f);
        var forward = new Vec3(0, 0, .06f).sub(eye).normalized();
        var right = new Vec3(0, 1, 0).cross(forward).normalized();
        var up = forward.cross(right);
        float height = .8f;
        var horizontal = right.scale(height * 1.6f);
        var vertical = up.scale(height);
        var camera =
                new Camera(
                        eye,
                        new Rect(
                                eye.add(forward)
                                        .sub(horizontal.scale(.5f))
                                        .sub(vertical.scale(.5f)),
                                horizontal,
                                vertical));
        var lighting = GameLighting.defaults();
        var world =
                new WorldSnapshot(
                        0,
                        RifleMesh.instances("rifle/", Transform.IDENTITY, 0),
                        List.of(),
                        List.of(lighting.sun()),
                        lighting.sky());
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSampleTarget(16)
                        .withSamplesPerBatch(8);
        try (var session =
                RenderEngine.openSession(world, new RenderView(camera, 640, 400), settings)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                try (var image = session.acquireImage()) {
                    if (image != null && image.samples() >= 16) {
                        Files.createDirectories(Path.of("out/game"));
                        ImageIO.write(
                                GameRenderer.copyImage(image),
                                "png",
                                Path.of("out/game/rifle-mesh.png").toFile());
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Missing rifle mesh render");
        }
    }
}
