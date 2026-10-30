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
    void entranceIsOneFlatOpticWithoutGlassFacets() {
        var parts =
                ScopeMesh.instances("scope/", Transform.IDENTITY).stream()
                        .filter(p -> p.material().viewRelay() != null)
                        .toList();
        assertEquals(1, parts.size());
        assertEquals(4f, parts.getFirst().material().viewRelay().magnification());
        assertEquals(128, ((engine.PolygonMesh) parts.getFirst().geometry()).faces().size());
        var query = query(parts);
        for (float slope : new float[] {.025f, .05f, .075f}) {
            var direction = new Vec3(slope, .013f, 1).normalized();
            var origin = new Vec3(0, ScopeMesh.AXIS_Y, ScopeMesh.EYEPIECE_Z - ScopeMesh.EYE_RELIEF);
            var hit = query.nearest(new RayQuery(origin, direction)).orElseThrow();
            assertTrue(hit.frontFace());
            assertTrue(hit.worldNormal().sub(new Vec3(0, 0, -1)).length() < .000001f);
        }
    }

    @Test
    void relayMaterialSurvivesScenePersistenceAndRejectsInvalidGeometry() throws Exception {
        var optic = ScopeMesh.instances("scope/", Transform.IDENTITY).getFirst();
        var document = new SceneDocument();
        document.transact(
                edit ->
                        edit.assignGeometry(
                                edit.createNode("optic", null, Transform.IDENTITY),
                                edit.createGeometry("entrance", optic.geometry()),
                                edit.createMaterial("optic", optic.material())));
        var path = Path.of("out/game/view-relay.scene.xml");
        java.nio.file.Files.createDirectories(path.getParent());
        engine.SceneFiles.save(path, document.snapshot());
        var restored = engine.SceneFiles.load(path).toWorldSnapshot().instances().getFirst();
        assertEquals(optic.material().viewRelay(), restored.material().viewRelay());
        assertEquals(
                optic.material().viewRelay(),
                optic.material().withColor(new Vec3(.1f, .1f, .1f)).viewRelay());
        boolean rejected = false;
        try {
            new SceneInstance("invalid", SceneInstance.box(), Transform.IDENTITY, optic.material());
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
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

    @Test
    void rifleSupportsDoNotObstructTheClearOpticalTube() {
        var query = query(RifleMesh.instances("rifle/", Transform.IDENTITY, 0));
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI / 16;
            var origin =
                    new Vec3(
                            (float) Math.cos(angle) * .0239f,
                            ScopeMesh.AXIS_Y + (float) Math.sin(angle) * .0239f,
                            ScopeMesh.EYEPIECE_Z + .001f);
            var hit = query.nearest(new RayQuery(origin, new Vec3(0, 0, 1)));
            assertTrue(hit.isEmpty() || hit.orElseThrow().distance() > .385f);
        }
    }

    @Test
    void gameplayScopeRendersWithWorldOnBothSidesAtLowDepth() throws Exception {
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
                        .withPathDepth(2)
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
    void patrolScopeCanCrossPlayerSightWithoutInvalidGlass() {
        var camera = new GameNavigation().view().camera();
        var pose = RifleMesh.firstPersonPose(camera);
        var player = RifleMesh.firstPerson(camera, 0);
        var overlapping = new ArrayList<>(player);
        overlapping.addAll(RifleMesh.instances("patrol/0/rifle/", pose, 0));
        var settings = RenderSettings.defaults().withPathDepth(8);
        var view = new RenderView(camera, 64, 64);
        assertTrue(
                RenderCapabilities.GPU
                        .issues(
                                new WorldSnapshot(0, overlapping, List.of(), List.of()),
                                view,
                                settings)
                        .isEmpty());
        var safe = new ArrayList<>(player);
        var patrol = RifleMesh.patrolInstances("patrol/0/rifle/", pose, 0);
        safe.addAll(patrol);
        assertTrue(patrol.stream().noneMatch(part -> part.material().viewRelay() != null));
        assertEquals(
                1L, player.stream().filter(part -> part.material().viewRelay() != null).count());
        assertTrue(player.stream().noneMatch(part -> part.material().clearGlass()));
        assertTrue(
                RenderCapabilities.GPU
                        .issues(new WorldSnapshot(1, safe, List.of(), List.of()), view, settings)
                        .isEmpty());
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
        var glass =
                new SceneInstance(
                        "glass",
                        SceneInstance.box(),
                        Transform.IDENTITY,
                        new Material("glass", Vec3.ZERO, Material.Kind.DIELECTRIC));
        var nested = new ArrayList<>(List.of(glass));
        nested.add(
                glass.withTransform(
                        new Transform(new Vec3(.001f, 0, 0), Vec3.ZERO, new Vec3(1, 1, 1))));
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
