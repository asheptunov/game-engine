package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import engine.Camera;
import engine.Material;
import engine.SceneInstance;
import engine.Transform;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.List;

public final class ShooterGameTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static Camera camera(float x, float y) {
        var eye = new Vec3(x, y, 0);
        return new Camera(
                eye, new Rect(eye.add(new Vec3(-1, -1, 1)), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
    }

    private static SceneInstance box(String name, Vec3 position, Vec3 half) {
        return new SceneInstance(
                name,
                SceneInstance.box(),
                new Transform(position, Vec3.ZERO, half),
                new Material(name, new Vec3(.4f, .4f, .4f)));
    }

    private static SceneInstance floor() {
        return box("floor", new Vec3(0, -1, 20), new Vec3(100, 1, 100));
    }

    private static void advance(ShooterGame game, Camera camera, double seconds) {
        for (int step = 0; step < Math.round(seconds * 120); step++) {
            game.advance(ShooterGame.STEP, camera, true);
        }
    }

    @Test
    void lowerProfileReducesDetectionAndShotRevealsPosition() {
        var standing = new ShooterGame(List.of(floor()));
        var prone = new ShooterGame(List.of(floor()));
        prone.playerHeight(.65f);
        var camera =
                camera(-2, 1.7f)
                        .transformed(
                                new Transform(new Vec3(0, 0, -25), Vec3.ZERO, new Vec3(1, 1, 1)));
        advance(standing, camera, 3.05);
        advance(prone, camera, 3.05);
        assertTrue(standing.tracers().stream().anyMatch(t -> !t.player()));
        assertTrue(prone.tracers().isEmpty());
        prone.fire(camera);
        advance(prone, camera, 2.5);
        assertTrue(prone.tracers().stream().anyMatch(t -> !t.player()));
    }

    @Test
    void weaponSnapshotRestoresSwayAndRecoilExactly() throws Exception {
        var navigation = new GameNavigation();
        navigation.combat(true);
        navigation.capture();
        navigation.recoil();
        navigation.advance(.12);
        var state = navigation.snapshot();
        var path =
                java.nio.file.Files.createTempFile(
                        java.nio.file.Path.of("out/game"), "weapon-", ".properties");
        CaptureCodec.save(path, state);
        var restored = new GameNavigation();
        restored.restore(CaptureCodec.load(path, GameNavigation.State.class));
        assertEquals(navigation.view(), restored.view());
        navigation.advance(.05);
        restored.advance(.05);
        assertEquals(navigation.view(), restored.view());
        java.nio.file.Files.delete(path);
    }

    @Test
    void detailedPatrolsShareMeshesAndAnimateTheirHitBounds() {
        var game = new ShooterGame(List.of(floor()));
        var initial = game.instances();
        assertTrue(initial.stream().filter(i -> i.name().contains("camo")).count() >= 100);
        game.advance(.2, camera(50, 30), true);
        var moving = game.instances();
        assertEquals(initial.size(), moving.size());
        for (int i = 0; i < initial.size(); i++) {
            assertTrue(initial.get(i).geometry() == moving.get(i).geometry());
        }
        assertFalse(initial.equals(moving));
    }

    @Test
    void foliageConcealsButDoesNotStopShotsAndPatrolsAreDispersed() {
        var leaves = box("Foliage test", new Vec3(-2, 1, 5), new Vec3(30, 3, .05f));
        var game = new ShooterGame(List.of(floor(), leaves));
        var targets = game.status().targets();
        assertTrue(targets.getLast().feet().z() - targets.getFirst().feet().z() >= 80);
        assertTrue(Math.abs(targets.getLast().feet().x()) >= 25);
        var camera = camera(-2, 1.75f);
        advance(game, camera, 3.05);
        assertTrue(game.tracers().stream().noneMatch(t -> !t.player()));
        game.reset();
        game.fire(camera);
        game.advance(.15, camera, true);
        assertEquals(1, game.status().kills());
    }

    @Test
    void recoilAndSwayOnlyAdvanceDuringCapturedActivePlay() {
        var navigation = new GameNavigation();
        navigation.combat(true);
        navigation.capture();
        var before = navigation.view().camera();
        navigation.recoil();
        assertTrue(navigation.view().camera().forward().y() > before.forward().y());
        navigation.advance(.1);
        assertFalse(before.equals(navigation.view().camera()));
        navigation.release();
        assertEquals(before, navigation.view().camera());
        navigation.advance(.25);
        assertEquals(before, navigation.view().camera());
    }

    @Test
    void headshotTravelsThenKillsAndRemovesBody() {
        var game = new ShooterGame(List.of(floor()));
        var camera = camera(-2, 1.75f);
        assertTrue(game.fire(camera));
        assertEquals(100, game.status().targets().getFirst().health());
        game.advance(.025, camera, true);
        assertEquals(100, game.status().targets().getFirst().health());
        game.advance(.1, camera, true);
        assertEquals(0, game.status().targets().getFirst().health());
        assertEquals(1, game.status().kills());
        assertTrue(
                game.instances().stream()
                        .noneMatch(instance -> instance.name().startsWith("patrol/0/")));
    }

    @Test
    void torsoDamageAndThinCoverOcclusion() {
        var camera = camera(-2, 1.15f);
        var game = new ShooterGame(List.of(floor()));
        game.fire(camera);
        game.advance(.15, camera, true);
        int health = game.status().targets().getFirst().health();
        assertTrue(health >= 30 && health <= 33);
        var covered =
                new ShooterGame(
                        List.of(
                                floor(),
                                box("thin cover", new Vec3(-2, 1, 5), new Vec3(1, 2, .01f))));
        covered.fire(camera);
        covered.advance(.15, camera, true);
        assertEquals(100, covered.status().targets().getFirst().health());
        assertEquals(100, ShooterGame.damage(ShooterGame.Region.HEAD, 800));
        assertTrue(
                ShooterGame.damage(ShooterGame.Region.LEG, 50)
                        > ShooterGame.damage(ShooterGame.Region.ARM, 50));
        assertTrue(
                ShooterGame.damage(ShooterGame.Region.TORSO, 250)
                        < ShooterGame.damage(ShooterGame.Region.TORSO, 10));
    }

    @Test
    void boltReloadPauseAndRestart() {
        var game = new ShooterGame(List.of(floor()));
        var camera = camera(50, 30);
        assertTrue(game.fire(camera));
        assertFalse(game.fire(camera));
        game.advance(.25, camera, false);
        assertEquals(1.25, game.status().cooldown());
        advance(game, camera, 1.3);
        game.reload();
        assertFalse(game.fire(camera));
        advance(game, camera, 2.9);
        assertEquals(5, game.status().rounds());
        assertEquals(29, game.status().reserve());
        game.reset();
        assertEquals(30, game.status().reserve());
        assertEquals(100, game.status().health());
    }

    @Test
    void returnFireAndPausedPatrols() {
        var game = new ShooterGame(List.of(floor()));
        var camera = camera(-2, 1.7f);
        var before = game.status();
        game.advance(.25, camera, false);
        assertEquals(before, game.status());
        advance(game, camera, 15);
        assertTrue(game.status().health() < 100);
        assertFalse(
                game.status()
                        .targets()
                        .getFirst()
                        .feet()
                        .equals(before.targets().getFirst().feet()));
    }

    @Test
    void scopeUsesSceneOpticsAndPreservesUnscopedCamera() {
        var navigation = new GameNavigation();
        navigation.optics(.01f, 7);
        var before = navigation.view().camera();
        navigation.capture();
        navigation.scoped(true);
        for (int i = 0; i < 8; i++) {
            navigation.advance(.125);
        }
        var scoped = navigation.view().camera();
        assertEquals(0f, scoped.aperture());
        assertTrue(
                Math.abs(before.sensor().edge2().length() / scoped.sensor().edge2().length() - 1.5)
                        < .0001);
        assertEquals(before.focus(), scoped.focus());
        navigation.release();
        assertEquals(before, navigation.view().camera());
    }

    @Test
    void gravityDropsShotIntoHeadAndSubstepsAgree() {
        var camera = camera(-2, 1.99f);
        var whole = new ShooterGame(List.of(floor()));
        var split = new ShooterGame(List.of(floor()));
        whole.fire(camera);
        split.fire(camera);
        whole.advance(.2, camera, true);
        advance(split, camera, .2);
        assertEquals(0, whole.status().targets().getFirst().health());
        assertEquals(whole.status(), split.status());
    }

    @Test
    void combatFramesKeepWeaponAndCameraCoherentAcrossWakeupsAndSuspension() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        navigation.active(false);
        navigation.pose(new Vec3(-2, 1.7f, 0), 0, 0);
        var frames = new java.util.concurrent.CopyOnWriteArrayList<GameRenderer.Frame>();
        var finished = new java.util.concurrent.CountDownLatch(1);
        try (var renderer =
                new GameRenderer(
                        navigation, engine.WorldSnapshot.of(List.of(floor()), List.of()), "cpu")) {
            renderer.enableShooter();
            renderer.refinement(false);
            renderer.observePublications(
                    frame -> {
                        assertEquals(navigation.view().camera(), frame.camera());
                        var rifle =
                                frame.context().world().instances().stream()
                                        .filter(
                                                instance ->
                                                        instance.name().startsWith("player/rifle/"))
                                        .toList();
                        assertEquals(RifleMesh.firstPerson(frame.camera(), 0, 0f), rifle);
                        frames.add(frame);
                        if (frames.size() == 6) {
                            navigation.active(false);
                            finished.countDown();
                        } else {
                            navigation.pose(
                                    new Vec3(-2 + frames.size() * .1f, 1.7f, 0),
                                    frames.size() * .01,
                                    0);
                        }
                    });
            navigation.active(true);
            assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS));
            renderer.observePublications(null);
            assertEquals(null, renderer.error());
            long last = renderer.frame().publicationNanos();
            int[] pixels = frames.getFirst().image().getRGB(0, 0, 96, 64, null, 0, 96);
            Thread.sleep(100);
            assertEquals(last, renderer.frame().publicationNanos());
            assertEquals(6, frames.size());
            navigation.pose(new Vec3(1, 2, 0), .2, 0);
            renderer.configure(settings -> settings.withSampleTarget(3));
            navigation.active(true);
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (renderer.frame().samples() != 3 && System.nanoTime() < deadline) {
                assertEquals(null, renderer.error());
                Thread.sleep(5);
            }
            assertEquals(3L, renderer.frame().samples());
            assertEquals(navigation.view().camera(), renderer.frame().camera());
            last = renderer.frame().publicationNanos();
            Thread.sleep(100);
            assertEquals(last, renderer.frame().publicationNanos());
            assertTrue(
                    java.util.Arrays.equals(
                            pixels, frames.getFirst().image().getRGB(0, 0, 96, 64, null, 0, 96)));
        }
    }

    @Test
    void liveCombatPublishesAndWritesScopePreview() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(320, 200);
        navigation.pose(new Vec3(-2, 1.7f, 0), 0, 0);
        try (var renderer =
                new GameRenderer(
                        navigation, engine.WorldSnapshot.of(List.of(floor()), List.of()), "cpu")) {
            renderer.enableShooter();
            navigation.capture();
            navigation.scoped(true);
            long deadline = System.nanoTime() + 15_000_000_000L;
            GameRenderer.Frame frame = null;
            while (System.nanoTime() < deadline) {
                frame = renderer.frame();
                assertEquals(null, renderer.error());
                if (frame != null
                        && frame.camera().aperture() == 0f
                        && frame.context().world().instances().stream()
                                .anyMatch(value -> value.name().startsWith("patrol/"))) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(frame != null && frame.camera().aperture() == 0f);
            assertTrue(frame.context().world().instances().size() > 1);
            var image =
                    new java.awt.image.BufferedImage(
                            960, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var draw = image.createGraphics();
            try {
                draw.drawImage(frame.image(), 0, 0, 960, 600, null);
                ShooterOverlay.draw(draw, 960, 600, navigation, renderer.shooter().status());
            } finally {
                draw.dispose();
            }
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("out/game"));
            javax.imageio.ImageIO.write(
                    image, "png", java.nio.file.Path.of("out/game/shooter-scope.png").toFile());
        }
    }

    @Test
    void tracersFollowFlightStopAtCoverFadeAndReset() throws Exception {
        var wall = box("cover", new Vec3(0, 3, 8), new Vec3(20, 5, .01f));
        var game = new ShooterGame(List.of(floor(), wall));
        var camera = camera(0, 3);
        game.fire(camera);
        assertTrue(game.tracers().isEmpty());
        game.advance(.025, camera, true);
        var first = game.tracers().getFirst();
        assertTrue(first.player());
        assertTrue(first.from().z() > 1 && first.to().z() > first.from().z());
        assertTrue(first.to().sub(first.from()).length() < 1.51f);
        game.advance(.075, camera, true);
        assertTrue(game.tracers().stream().allMatch(t -> t.to().z() <= 8.001));
        assertTrue(game.tracers().getLast().to().z() > 7.98);
        var beforePause = game.tracers();
        game.advance(.25, camera, false);
        assertEquals(beforePause, game.tracers());
        assertFalse(game.tracerVisible(camera.eye(), new Vec3(0, 3, 10)));
        var image =
                new java.awt.image.BufferedImage(
                        960, 600, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var draw = image.createGraphics();
        try {
            TracerOverlay.draw(draw, 960, 600, camera, game);
        } finally {
            draw.dispose();
        }
        long pixels =
                java.util.Arrays.stream(image.getRGB(0, 0, 960, 600, null, 0, 960))
                        .filter(rgb -> rgb != 0)
                        .count();
        assertTrue(pixels > 5);
        javax.imageio.ImageIO.write(
                image, "png", java.nio.file.Path.of("out/game/tracer-preview.png").toFile());
        game.advance(.25, camera, true);
        assertTrue(game.tracers().isEmpty());
        game.reset();
        assertTrue(game.tracers().isEmpty());
    }

    @Test
    void tracerPathCurvesUnderGravityAndEnemyShotsAreDistinct() {
        var game = new ShooterGame(List.of(floor()));
        var camera = camera(50, 30);
        game.fire(camera);
        game.advance(.025, camera, true);
        var early = game.tracers().getFirst();
        float earlySlope = early.to().sub(early.from()).y() / early.to().sub(early.from()).z();
        game.advance(.1, camera, true);
        var late = game.tracers().getLast();
        float lateSlope = late.to().sub(late.from()).y() / late.to().sub(late.from()).z();
        assertTrue(lateSlope < earlySlope);
        var enemies = new ShooterGame(List.of(floor()));
        advance(enemies, camera(-2, 1.7f), 3.05);
        assertTrue(enemies.tracers().stream().anyMatch(t -> !t.player()));
    }

    @Test
    void adventureTracerOverlayCostStaysBounded() {
        var game = new ShooterGame(AdventureWorld.create().instances());
        var camera = camera(0, 12);
        advance(game, camera, 3.05);
        game.fire(camera);
        game.advance(.08, camera, true);
        assertTrue(!game.tracers().isEmpty() && game.tracers().size() <= 256);
        var image =
                new java.awt.image.BufferedImage(
                        960, 600, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var draw = image.createGraphics();
        try {
            for (int i = 0; i < 20; i++) {
                TracerOverlay.draw(draw, 960, 600, camera, game);
            }
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                TracerOverlay.draw(draw, 960, 600, camera, game);
            }
            System.out.printf(
                    java.util.Locale.ROOT,
                    "Tracer overlay: %d segments, %.3f ms per draw%n",
                    game.tracers().size(),
                    (System.nanoTime() - start) / 1e8);
        } finally {
            draw.dispose();
        }
    }
}
