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
    void firstPersonSharesCapturedCameraDuringContinuousLook() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        navigation.capture();
        try (var renderer =
                new GameRenderer(
                        navigation,
                        BlockWorld.gallery(),
                        System.getProperty("scope.backend", "cpu"))) {
            renderer.enableShooter();
            long deadline = System.nanoTime() + 15_000_000_000L;
            long previous = -1;
            int checked = 0;
            while (System.nanoTime() < deadline && checked < 6) {
                navigation.look(12, checked % 2 == 0 ? 3 : -3);
                var frame = renderer.frame();
                if (frame != null
                        && frame.publicationNanos() != previous
                        && frame.context() != null) {
                    if (checkCapturedRifle(frame)) {
                        previous = frame.publicationNanos();
                        checked++;
                    }
                }
                Thread.sleep(10);
            }
            assertEquals(null, renderer.error());
            assertEquals(6, checked);
        }
    }

    private static boolean checkCapturedRifle(GameRenderer.Frame frame) {
        var parts =
                frame.context().world().instances().stream()
                        .filter(part -> part.name().startsWith("player/rifle/"))
                        .toList();
        var expected = RifleMesh.firstPersonPose(frame.camera());
        for (var part : parts) {
            if (part.name().contains("body/")) {
                assertEquals(expected, part.transform());
            }
        }
        return !parts.isEmpty();
    }

    @Test
    void worldGeometryOccludesFirstPersonRifle() {
        var camera = new GameNavigation().view().camera();
        var parts = new java.util.ArrayList<>(RifleMesh.firstPerson(camera, 0));
        var target = RifleMesh.firstPersonPose(camera).point(new Vec3(0, -.08f, -.35f));
        var direction = target.sub(camera.eye()).normalized();
        var ray = new engine.RayQuery(camera.eye(), direction);
        assertTrue(query(parts).nearest(ray).isPresent());
        parts.add(
                new engine.SceneInstance(
                        "cover",
                        engine.SceneInstance.box(),
                        new Transform(
                                camera.eye().add(direction.scale(.1f)),
                                Vec3.ZERO,
                                new Vec3(.05f, .05f, .05f)),
                        new engine.Material("cover", new Vec3(.5f, .5f, .5f))));
        var hit = query(parts).nearest(ray).orElseThrow();
        assertTrue(hit.distance() < .2f);
    }

    private static engine.SpatialQuery query(List<engine.SceneInstance> instances) {
        return engine.SpatialQuery.prepare(
                new engine.SceneDocument()
                        .transact(
                                edit -> {
                                    for (var part : instances) {
                                        var node =
                                                edit.createNode(
                                                        part.name(), null, part.transform());
                                        edit.assignGeometry(
                                                node,
                                                edit.createGeometry(part.name(), part.geometry()),
                                                edit.createMaterial(part.name(), part.material()));
                                    }
                                }));
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
        assertTrue(resting.size() <= 12);
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
                        .withPathDepth(6)
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
