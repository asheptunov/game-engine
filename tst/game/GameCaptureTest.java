package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.input.Modifiers;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

public final class GameCaptureTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    private static Path root() throws Exception {
        return Files.createTempDirectory(Path.of("out"), "capture-test-");
    }

    private static GameRenderer.Frame await(GameRenderer renderer) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (renderer.frame() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(renderer.frame() != null);
        return renderer.frame();
    }

    @Test
    void fogQualitySettingsSurviveEditsAndOlderCapturesUseFull() throws Exception {
        var settings =
                engine.RenderSettings.defaults()
                        .withFogDownsample(4)
                        .withFogLightingGrid(true)
                        .withFogJitter(true)
                        .withPathDepth(2)
                        .withSeed(3)
                        .withRestartRevision(4)
                        .withSampleTarget(5)
                        .withSamplesPerBatch(2)
                        .withPaused(false)
                        .withAcceleration(true)
                        .withWorkers(1)
                        .withTileSize(16)
                        .withTemporal(false, 2)
                        .withInteractive(false, 20, 64, 64)
                        .withTemporalBudget(false, 2, .5);
        assertEquals(4, settings.fogDownsample());
        assertTrue(settings.fogLightingGrid());
        assertTrue(settings.fogJitter());
        Path file = root().resolve("settings.properties");
        CaptureCodec.save(file, settings);
        assertEquals(settings, CaptureCodec.load(file, engine.RenderSettings.class));
        var old =
                Files.readString(file)
                        .replace("data.fogDownsample=4", "")
                        .replace("data.fogLightingGrid=true", "")
                        .replace("data.fogJitter=true", "");
        Files.writeString(file, old);
        assertEquals(
                settings.withFogDownsample(1).withFogLightingGrid(false).withFogJitter(false),
                CaptureCodec.load(file, engine.RenderSettings.class));
        Files.writeString(file, old + "\ndata.fogDownsample=3\n");
        boolean rejected = false;
        try {
            CaptureCodec.load(file, engine.RenderSettings.class);
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void selfContainedSnapshotKeepsDisplayedAndCurrentStatesSeparate() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        var source = BlockWorld.gallery().snapshot();
        try (var renderer =
                new GameRenderer(
                        navigation, source, System.getProperty("capture.backend", "cpu"))) {
            renderer.configure(settings -> settings.withPathDepth(0).withSampleTarget(1));
            var shown = await(renderer);
            renderer.displayed(shown);
            navigation.pose(new Vec3(1, 3, -5), .2, -.1);
            renderer.atmospherePreset(true);
            renderer.fogPreset();
            renderer.configure(
                    settings ->
                            settings.withFogDownsample(4)
                                    .withFogLightingGrid(true)
                                    .withFogJitter(true));
            renderer.exposure(1.5f);
            var expectedNavigation = navigation.snapshot();
            var expectedControls = renderer.captureControls();
            var folder = renderer.captureBundle(false, root()).get(30, TimeUnit.SECONDS);
            var capture = GameCapture.load(folder);
            assertEquals(expectedNavigation, capture.manifest().current().navigation());
            assertEquals(expectedControls, capture.manifest().current().controls());
            assertEquals(shown.camera(), capture.manifest().displayed().view().camera());
            assertTrue(
                    !capture.manifest()
                            .current()
                            .navigation()
                            .position()
                            .equals(shown.camera().eye()));
            assertEquals(
                    source.instances(),
                    capture.world(capture.manifest().displayed().world()).instances());
            assertTrue(!capture.manifest().current().world().sky().fog().isEmpty());
            var screenshot = ImageIO.read(folder.resolve("displayed.png").toFile());
            assertEquals(
                    shown.image().getRGB(0, 0, 96, 64, null, 0, 96),
                    screenshot.getRGB(0, 0, 96, 64, null, 0, 96));
            var rendered = folder.resolve("reproduced.png");
            CaptureReplay.render(
                    capture, rendered, System.getProperty("capture.backend", "cpu"), true, 1);
            var result = ImageIO.read(rendered.toFile());
            assertEquals(
                    screenshot.getRGB(0, 0, 96, 64, null, 0, 96),
                    result.getRGB(0, 0, 96, 64, null, 0, 96));
            assertTrue(CaptureReplay.replay(capture).startsWith("NO MOTION"));
        }
    }

    @Test
    void historyReplaysPhysicsInputsEditsAndDetectsDivergence() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            renderer.configure(settings -> settings.withPathDepth(0).withSampleTarget(1));
            renderer.displayed(await(renderer));
            navigation.walking(true);
            navigation.capture();
            for (int i = 0; i < 160; i++) {
                navigation.advance(1.0 / 120);
            }
            navigation.key(KeyInput.press(KeyCode.W, Modifiers.NONE));
            navigation.key(KeyInput.press(KeyCode.SPACE, Modifiers.NONE));
            for (int i = 0; i < 60; i++) {
                navigation.advance(1.0 / 120);
            }
            navigation.look(30, -10);
            renderer.timeOfDay(17.5);
            renderer.fogPreset();
            for (int i = 0; i < 60; i++) {
                navigation.advance(1.0 / 120);
            }
            navigation.release();
            var folder = renderer.captureBundle(true, root()).get(30, TimeUnit.SECONDS);
            var capture = GameCapture.load(folder);
            assertTrue(capture.timeline().motions().size() >= 280);
            assertTrue(capture.timeline().states().size() >= 2);
            assertTrue(CaptureReplay.replay(capture).startsWith("PASS"));
            var steps = new java.util.ArrayList<>(capture.timeline().motions());
            var first = steps.getFirst();
            steps.set(
                    0,
                    new GameCapture.Motion(
                            first.nanos(),
                            first.seconds() * 2,
                            first.scene(),
                            first.edit(),
                            first.expected()));
            var changed =
                    new GameCapture.Loaded(
                            folder,
                            capture.manifest(),
                            new GameCapture.Timeline(
                                    steps,
                                    capture.timeline().states(),
                                    capture.timeline().pictures(),
                                    0),
                            capture.scenes());
            boolean rejected = false;
            try {
                CaptureReplay.replay(changed);
            } catch (IllegalStateException expected) {
                rejected = expected.getMessage().contains("DIVERGED");
            }
            assertTrue(rejected);
            renderer.restoreCapture(capture);
            assertEquals(
                    capture.manifest().current().navigation().body(), navigation.snapshot().body());
            assertTrue(!navigation.captured());
            assertTrue(!renderer.dayCycle());
            Files.writeString(folder.resolve("capture.properties"), "corrupted");
            rejected = false;
            try {
                GameCapture.load(folder);
            } catch (java.io.IOException expected) {
                rejected = expected.getMessage().contains("checksum");
            }
            assertTrue(rejected);
        }
    }

    @Test
    void recorderBoundsAndIncompleteExportAreExplicit() throws Exception {
        var recorder = new GameRecording();
        var state = new GameNavigation().snapshot();
        for (int i = 0; i < GameRecording.MAX_MOTIONS + 5; i++) {
            recorder.motion(i, .01, state, state, java.util.List.of());
        }
        assertEquals(GameRecording.MAX_MOTIONS, recorder.freeze(10_000, true).motions().size());
        assertEquals(5L, recorder.freeze(10_000, true).dropped());
        assertEquals(
                0, recorder.freeze(GameRecording.WINDOW_NANOS + 20_000, true).motions().size());
        var folder = root();
        Files.writeString(folder.resolve("INCOMPLETE"), "unfinished");
        boolean rejected = false;
        try {
            GameCapture.load(folder);
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void fullWindowExportRemainsBoundedAndReplays() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        var world = BlockWorld.gallery().snapshot();
        GameCapture.Controls controls;
        try (var renderer = new GameRenderer(navigation, world, "cpu")) {
            controls = renderer.captureControls();
        }
        navigation.active(true);
        navigation.capture();
        navigation.key(KeyInput.press(KeyCode.W, Modifiers.NONE));
        var recorder = new GameRecording();
        for (int i = 0; i < 8000; i++) {
            var before = navigation.snapshot();
            navigation.advance(.004);
            recorder.motion(i * 4_000_000L, .004, before, navigation.snapshot(), world.instances());
        }
        long now = 8000 * 4_000_000L;
        var pending =
                new GameCapture.Pending(
                        world,
                        controls,
                        navigation.snapshot(),
                        null,
                        recorder.freeze(now, true),
                        "cpu",
                        "test",
                        now,
                        true);
        long started = System.nanoTime();
        var folder = GameCapture.write(root(), pending);
        var loaded = GameCapture.load(folder);
        assertTrue(CaptureReplay.replay(loaded).startsWith("PASS"));
        assertEquals(7500, loaded.timeline().motions().size());
        assertTrue(Files.size(folder.resolve("timeline.properties.gz")) < 16 * 1024 * 1024);
        System.out.println(
                "Full-window capture: "
                        + loaded.timeline().motions().size()
                        + " updates, compressed timeline="
                        + Files.size(folder.resolve("timeline.properties.gz"))
                        + " bytes; export/load/replay="
                        + (System.nanoTime() - started) / 1e6
                        + " ms");
    }

    @Test
    void failedExportLeavesGameplayAliveAndCanRetry() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            var invalidRoot = root().resolve("file");
            Files.writeString(invalidRoot, "not a directory");
            boolean rejected = false;
            try {
                renderer.captureBundle(false, invalidRoot).get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                rejected = true;
            }
            assertTrue(rejected);
            assertEquals(null, renderer.error());
            var saved = renderer.captureBundle(false, root()).get(10, TimeUnit.SECONDS);
            assertTrue(Files.exists(saved.resolve("capture.properties")));
        }
    }

    @Test
    void fullAdventureGeometryRoundTripsWithoutPresetRegeneration() throws Exception {
        var original = AdventureWorld.create().instances();
        var path = root().resolve("world.properties.gz");
        CaptureCodec.save(path, CaptureScene.capture(original));
        var restored = CaptureCodec.load(path, CaptureScene.class).restore();
        assertEquals(original, restored);
        assertTrue(restored.getFirst().geometry() != original.getFirst().geometry());
    }

    @Test
    void captureKeysAreBoundAcrossSettingsFocusAndPaintSuppliesDisplayedImage() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            await(renderer);
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> {
                        var panel = new GamePanel(navigation, renderer);
                        try {
                            panel.setSize(960, 600);
                            var pixels =
                                    new java.awt.image.BufferedImage(
                                            960, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
                            var graphics = pixels.createGraphics();
                            try {
                                panel.paint(graphics);
                            } finally {
                                graphics.dispose();
                            }
                            var input =
                                    panel.getInputMap(
                                            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
                            assertEquals(
                                    "capture-119",
                                    input.get(
                                            javax.swing.KeyStroke.getKeyStroke(
                                                    java.awt.event.KeyEvent.VK_F8, 0, false)));
                            assertEquals(
                                    "capture-120",
                                    input.get(
                                            javax.swing.KeyStroke.getKeyStroke(
                                                    java.awt.event.KeyEvent.VK_F9, 0, false)));
                        } finally {
                            panel.close();
                        }
                    });
            // Close stops the writer: export cannot silently queue work after shutdown.
            boolean rejected = false;
            try {
                renderer.captureBundle(false, root()).get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                rejected = true;
            }
            assertTrue(rejected);
        }
    }
}
