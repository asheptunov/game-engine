package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertSame;
import static harness.Assertions.assertTrue;

import engine.RenderEngine;
import engine.RenderSettings;
import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.input.Modifiers;

import harness.SuiteRunner;
import harness.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class SampleGameTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static void key(GameNavigation navigation, KeyCode code, boolean pressed) {
        navigation.key(new KeyInput(code, pressed, Modifiers.NONE));
    }

    private static void near(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < .0001);
    }

    @Test
    void flightUsesElapsedTimeNormalizedHeadingAndPhysicalRelease() {
        var navigation = new GameNavigation();
        var spawn = navigation.view().camera().eye();
        key(navigation, KeyCode.W, true);
        navigation.advance(.1);
        assertEquals(spawn, navigation.view().camera().eye());
        navigation.capture();
        key(navigation, KeyCode.W, true);
        key(navigation, KeyCode.W, true);
        key(navigation, KeyCode.D, true);
        key(navigation, KeyCode.SPACE, true);
        for (int index = 0; index < 10; index++) {
            navigation.advance(.1);
        }
        near(3, navigation.view().camera().eye().sub(spawn).length());
        navigation.reset();
        key(navigation, KeyCode.W, true);
        for (int index = 0; index < 100; index++) {
            navigation.advance(.01);
        }
        near(3, navigation.view().camera().eye().z() - spawn.z());
        key(navigation, KeyCode.W, false);
        var stopped = navigation.view().camera().eye();
        navigation.advance(.2);
        assertEquals(stopped, navigation.view().camera().eye());
        navigation.look(524, 0);
        key(navigation, KeyCode.W, true);
        navigation.advance(.1);
        near(.3, navigation.view().camera().eye().x() - stopped.x());
        navigation.reset();
        key(navigation, KeyCode.R_CTRL, true);
        navigation.advance(10);
        near(-.75, navigation.view().camera().eye().y() - spawn.y());
    }

    @Test
    void pitchFramingResetEscapeAndFocusTransitions() {
        var navigation = new GameNavigation();
        var initial = navigation.view().camera();
        navigation.capture();
        navigation.look(0, -100000);
        near(Math.sin(Math.toRadians(89)), navigation.view().camera().forward().y());
        navigation.look(0, 100000);
        near(-Math.sin(Math.toRadians(89)), navigation.view().camera().forward().y());
        navigation.resize(400, 800);
        near(60, navigation.view().camera().fov());
        near(
                .5,
                navigation.view().camera().sensor().edge1().length()
                        / navigation.view().camera().sensor().edge2().length());
        key(navigation, KeyCode.W, true);
        key(navigation, KeyCode.R, true);
        navigation.advance(.1);
        assertEquals(initial.eye(), navigation.view().camera().eye());
        key(navigation, KeyCode.W, true);
        key(navigation, KeyCode.ESCAPE, true);
        navigation.capture();
        navigation.advance(.1);
        assertEquals(initial.eye(), navigation.view().camera().eye());
        key(navigation, KeyCode.W, true);
        navigation.active(false);
        navigation.capture();
        assertFalse(navigation.captured());
        navigation.active(true);
        assertFalse(navigation.captured());
        navigation.capture();
        navigation.advance(.1);
        assertEquals(initial.eye(), navigation.view().camera().eye());
    }

    private static GameRenderer.Frame awaitFrame(GameRenderer renderer) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (renderer.frame() == null && System.nanoTime() < deadline) {
            assertEquals(null, renderer.error());
            Thread.sleep(10);
        }
        assertTrue(renderer.frame() != null);
        return renderer.frame();
    }

    @Test
    void liveMovementPublishesSuspendsResumesAndCloses() throws Exception {
        var navigation = new GameNavigation();
        navigation.resize(320, 200);
        var renderer = new GameRenderer(navigation, BlockWorld.gallery());
        try {
            var first = awaitFrame(renderer);
            int[] frozen = first.image().getRGB(0, 0, 320, 200, null, 0, 320);
            navigation.capture();
            key(navigation, KeyCode.W, true);
            long deadline = System.nanoTime() + 2_000_000_000L;
            long generation = first.generation();
            int fresh = 0;
            while (System.nanoTime() < deadline) {
                Thread.sleep(10);
                var frame = renderer.frame();
                if (frame.generation() != generation) {
                    generation = frame.generation();
                    fresh++;
                }
            }
            assertTrue(fresh >= 2);
            assertEquals(frozen, first.image().getRGB(0, 0, 320, 200, null, 0, 320));
            navigation.active(false);
            Thread.sleep(150);
            assertTrue(renderer.suspended());
            var stopped = navigation.view().camera().eye();
            navigation.active(true);
            Thread.sleep(100);
            assertFalse(renderer.suspended());
            assertFalse(navigation.captured());
            assertEquals(stopped, navigation.view().camera().eye());
            assertEquals(null, renderer.error());
        } finally {
            renderer.close();
        }
        renderer.close();
        assertTrue(renderer.closed());
        Thread.sleep(100);
        assertFalse(
                Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(
                                thread ->
                                        thread.isAlive()
                                                && thread.getName().equals("sample-game-update")));
    }

    @Test
    void missingAndTransparentAssetsNameTheirPaths() throws Exception {
        var missing = Path.of("out/game-check/no-such-texture.png");
        boolean rejected = false;
        try {
            GameTextures.load(missing);
        } catch (IllegalArgumentException failure) {
            rejected = failure.getMessage().contains(missing.toAbsolutePath().toString());
        }
        assertTrue(rejected);
        var transparent = Path.of("out/game-check/transparent.png");
        ImageIO.write(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", transparent.toFile());
        rejected = false;
        try {
            GameTextures.load(transparent);
        } catch (IllegalArgumentException failure) {
            rejected = failure.getMessage().contains(transparent.toAbsolutePath().toString());
        }
        assertTrue(rejected);
    }

    private static void detailPreview(
            engine.WorldSnapshot world, math.Vec3 eye, math.Vec3 target, Path output)
            throws Exception {
        var forward = target.sub(eye).normalized();
        var right = new math.Vec3(0, 1, 0).cross(forward).normalized();
        var up = forward.cross(right);
        var horizontal = right.scale(1.28f);
        var vertical = up.scale(.8f);
        var camera =
                new engine.Camera(
                        eye,
                        new engine.objects.Rect(
                                eye.add(forward)
                                        .sub(horizontal.scale(.5f))
                                        .sub(vertical.scale(.5f)),
                                horizontal,
                                vertical));
        try (var session =
                RenderEngine.openSession(
                        world,
                        new engine.RenderView(camera, 480, 300),
                        RenderSettings.defaults())) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                try (var image = session.acquireImage()) {
                    if (image != null) {
                        ImageIO.write(GameRenderer.copyImage(image), "png", output.toFile());
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("No detail preview");
        }
    }

    @Test
    void gallerySharesUnitGeometryAndWritesRepresentativePreviews() throws Exception {
        var world = BlockWorld.gallery().snapshot();
        assertEquals(128, world.instances().size());
        for (var instance : world.instances()) {
            assertSame(world.instances().getFirst().geometry(), instance.geometry());
            if (!instance.name().equals("block 3,0,8")) {
                near(1, instance.transform().vector(new math.Vec3(2, 0, 0)).length());
            }
            assertTrue(instance.material().textures() != null);
        }
        var navigation = new GameNavigation();
        var output = Path.of("benchmarks/game");
        Files.createDirectories(output);
        var renderer = new GameRenderer(navigation, BlockWorld.gallery());
        var panel = new GamePanel[1];
        try {
            awaitFrame(renderer);
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel[0] = new GamePanel(navigation, renderer);
                        panel[0].setSize(960, 600);
                        var image = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        try {
                            panel[0].paint(graphics);
                        } finally {
                            graphics.dispose();
                        }
                        try {
                            ImageIO.write(image, "png", output.resolve("g4-gallery.png").toFile());
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    });
        } finally {
            if (panel[0] != null) {
                SwingUtilities.invokeAndWait(panel[0]::close);
            } else {
                renderer.close();
            }
        }
        detailPreview(
                world,
                new math.Vec3(4.5f, 1.6f, 6),
                new math.Vec3(3, .2f, 8),
                output.resolve("g2-wood-detail.png"));
        detailPreview(
                world,
                new math.Vec3(-1, .2f, 3.5f),
                new math.Vec3(-1, 2, 5),
                output.resolve("g2-underside.png"));
        navigation.reset();
        navigation.resize(600, 800);
        try (var session =
                RenderEngine.openSession(world, navigation.view(), RenderSettings.defaults())) {
            long deadline = System.nanoTime() + 10_000_000_000L;
            boolean written = false;
            while (!written && System.nanoTime() < deadline) {
                session.request();
                try (var image = session.acquireImage()) {
                    if (image != null) {
                        ImageIO.write(
                                GameRenderer.copyImage(image),
                                "png",
                                output.resolve("g2-portrait.png").toFile());
                        written = true;
                    }
                }
                Thread.sleep(10);
            }
            assertTrue(written);
        }
    }
}
