package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.Material;
import engine.RayQuery;
import engine.RenderCapabilities;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.RenderView;
import engine.SceneDocument;
import engine.SceneInstance;
import engine.Sky;
import engine.SpatialQuery;
import engine.Transform;
import engine.WorldSnapshot;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

public final class ScopeOpticsTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void aimingAnimatesReversesAndReturnsWithoutSticking() {
        var navigation = new GameNavigation();
        navigation.capture();
        navigation.scoped(true);
        assertEquals(0f, navigation.aimAmount());
        navigation.advance(.1);
        float partial = navigation.aimAmount();
        assertTrue(partial > 0 && partial < 1);
        var camera = navigation.view().camera();
        var pose = RifleMesh.firstPersonPose(camera, partial);
        var hip = RifleMesh.firstPersonPose(camera, 0f);
        var sight = RifleMesh.firstPersonPose(camera, 1f);
        assertTrue(pose.position.sub(hip.position).length() > .01f);
        assertTrue(pose.position.sub(sight.position).length() > .01f);
        navigation.scoped(false);
        assertEquals(partial, navigation.aimAmount());
        navigation.advance(.15);
        assertTrue(navigation.aimAmount() < partial);
        navigation.scoped(true);
        for (int i = 0; i < 8; i++) {
            navigation.advance(.125);
        }
        assertEquals(1f, navigation.aimAmount());
        navigation.scoped(false);
        for (int i = 0; i < 8; i++) {
            navigation.advance(.125);
        }
        assertEquals(0f, navigation.aimAmount());
        navigation.scoped(true);
        navigation.advance(.1);
        navigation.active(false);
        assertEquals(0f, navigation.aimAmount());
        assertTrue(!navigation.scoped());
    }

    @Test
    void aimSpringUsesElapsedTimeAndScopesCloser() {
        var whole = new AimMotion();
        var split = new AimMotion();
        whole.advance(.2, true);
        for (int i = 0; i < 20; i++) {
            split.advance(.01, true);
        }
        assertTrue(Math.abs(whole.amount() - split.amount()) < .00001);
        whole.advance(Double.NaN, false);
        assertEquals(split.amount(), whole.amount());
        assertTrue(ScopeMesh.EYE_RELIEF < .08f);
    }

    @Test
    void lensesBendRaysAndMagnifyWithoutCameraZoom() {
        var parts =
                ScopeMesh.instances("scope/", Transform.IDENTITY).stream()
                        .filter(p -> p.material().clearGlass())
                        .toList();
        var query = query(parts);
        for (float slope : new float[] {.025f, .05f, .075f}) {
            var direction = new Vec3(slope, .013f, 1).normalized();
            var origin = new Vec3(0, ScopeMesh.AXIS_Y, ScopeMesh.EYEPIECE_Z - ScopeMesh.EYE_RELIEF);
            var output = transmit(query, origin, direction);
            float magnification = slope / (output.x() / output.z());
            System.out.println("Scope angular magnification: " + magnification);
            assertTrue(magnification > 3 && magnification < 6);
        }
    }

    private static SpatialQuery query(List<SceneInstance> parts) {
        return SpatialQuery.prepare(
                new SceneDocument()
                        .transact(
                                edit -> {
                                    for (var part : parts) {
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

    private static Vec3 transmit(SpatialQuery query, Vec3 origin, Vec3 direction) {
        for (int i = 0; i < 6; i++) {
            var hit = query.nearest(new RayQuery(origin, direction)).orElseThrow();
            var normal = hit.frontFace() ? hit.worldNormal() : hit.worldNormal().scale(-1);
            float eta = hit.frontFace() ? 1 / 1.5f : 1.5f;
            float cosine = -normal.dot(direction);
            float discriminant = 1 - eta * eta * (1 - cosine * cosine);
            assertTrue(discriminant > 0);
            direction =
                    direction
                            .scale(eta)
                            .add(normal.scale(eta * cosine - (float) Math.sqrt(discriminant)))
                            .normalized();
            origin = hit.worldPosition().sub(normal.scale(.001f));
        }
        assertTrue(query.nearest(new RayQuery(origin, direction)).isEmpty());
        return direction;
    }

    @Test
    void physicalScopeRendersWithWorldOnBothSides() throws Exception {
        var navigation = new GameNavigation();
        navigation.pose(new Vec3(0, 1.7f, 0), 0, 0);
        navigation.capture();
        navigation.scoped(true);
        for (int i = 0; i < 8; i++) {
            navigation.advance(.125);
        }
        var camera = navigation.view().camera();
        var parts = new ArrayList<>(RifleMesh.firstPerson(camera, 0, true));
        for (int i = -12; i <= 12; i++) {
            parts.add(
                    new SceneInstance(
                            "target/" + i,
                            SceneInstance.box(),
                            new Transform(
                                    new Vec3(i * .3f, 1.7f, 8), Vec3.ZERO, new Vec3(.15f, 3, .1f)),
                            new Material(
                                    "stripe",
                                    i % 2 == 0
                                            ? new Vec3(.9f, .15f, .08f)
                                            : new Vec3(.12f, .55f, .9f))));
        }
        var light = GameLighting.defaults();
        var world = new WorldSnapshot(0, parts, List.of(), List.of(light.sun()), light.sky());
        String backend = System.getProperty("scope.backend", "cpu");
        var settings =
                RenderSettings.defaults()
                        .withInteractive(false, 16.67, 0, 0)
                        .withPathDepth(8)
                        .withSampleTarget(16)
                        .withSamplesPerBatch(8);
        try (var session =
                RenderEngine.openSession(
                        world, new RenderView(camera, 480, 300), settings, backend)) {
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                if (session.failure() != null) {
                    throw session.failure();
                }
                try (var image = session.acquireImage()) {
                    if (image != null && image.samples() >= 16) {
                        var pixels = GameRenderer.copyImage(image);
                        java.nio.file.Files.createDirectories(Path.of("out/game"));
                        ImageIO.write(
                                pixels,
                                "png",
                                Path.of("out/game/scope-scene-" + backend + ".png").toFile());
                        assertTrue((pixels.getRGB(240, 140) & 0xffffff) != 0);
                        assertTrue((pixels.getRGB(20, 140) & 0xffffff) != 0);
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Scope render timed out");
        }
    }

    @Test
    void gpuRejectsNestedGlassAndAcceptsScope() {
        var camera = new GameNavigation().view().camera();
        var parts = ScopeMesh.instances("scope/", Transform.IDENTITY);
        var world = new WorldSnapshot(0, parts, List.of(), List.of(), Sky.BLACK);
        var settings =
                RenderSettings.defaults().withPathDepth(8).withInteractive(false, 16.67, 0, 0);
        assertTrue(
                RenderCapabilities.GPU
                        .issues(world, new RenderView(camera, 64, 64), settings)
                        .isEmpty());
        var nested = new ArrayList<>(parts);
        nested.add(
                parts.getFirst()
                        .withTransform(
                                new Transform(
                                        new Vec3(.001f, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1))));
        assertEquals(
                1,
                RenderCapabilities.GPU
                        .issues(
                                new WorldSnapshot(1, nested, List.of(), List.of()),
                                new RenderView(camera, 64, 64),
                                settings)
                        .size());
    }
}
