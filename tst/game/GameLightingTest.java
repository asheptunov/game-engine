package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

import engine.RenderEngine;
import engine.RenderSettings;
import engine.WorldSnapshot;

import harness.SuiteRunner;
import harness.Test;

import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class GameLightingTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void releasedPointerButtonsChangeLightingAndCameraResetPreservesIt() throws Exception {
        var navigation = new GameNavigation();
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            var panel = new GamePanel[1];
            try {
                SwingUtilities.invokeAndWait(
                        () -> {
                            panel[0] = new GamePanel(navigation, renderer);
                            panel[0].setSize(960, 600);
                            click(panel[0], 30);
                            assertEquals(GameLighting.Preset.MORNING, renderer.lighting().preset());
                            assertFalse(navigation.captured());
                            click(panel[0], 240);
                            assertEquals(GameLighting.Preset.EVENING, renderer.lighting().preset());
                            click(panel[0], 345);
                            assertFalse(renderer.lighting().skyEnabled());
                            navigation.reset();
                            assertEquals(
                                    new GameLighting(GameLighting.Preset.EVENING, false),
                                    renderer.lighting());
                            click(panel[0], 140);
                            assertEquals(GameLighting.Preset.NOON, renderer.lighting().preset());
                            click(panel[0], 345);
                            assertTrue(renderer.lighting().skyEnabled());
                        });
                long deadline = System.nanoTime() + 10_000_000_000L;
                while ((renderer.frame() == null || renderer.frame().samples() < 4)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(null, renderer.error());
                assertTrue(renderer.frame() != null && renderer.frame().samples() >= 4);
            } finally {
                if (panel[0] != null) {
                    SwingUtilities.invokeAndWait(panel[0]::close);
                }
            }
        }
    }

    private static void click(GamePanel panel, int x) {
        panel.dispatchEvent(
                new MouseEvent(
                        panel,
                        MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        x,
                        135,
                        1,
                        false,
                        MouseEvent.BUTTON1));
    }

    @Test
    void refinementControlReplacesRefinedImageAndResumesAfterMovement() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            var panel = new GamePanel[1];
            try {
                awaitSamples(renderer, frame -> frame.samples() >= 4);
                SwingUtilities.invokeAndWait(
                        () -> {
                            panel[0] = new GamePanel(navigation, renderer);
                            clickRefinement(panel[0]);
                            assertFalse(renderer.refinement());
                            assertFalse(navigation.captured());
                        });
                awaitSamples(renderer, frame -> frame.samples() == 1);
                long publication = renderer.frame().publicationNanos();
                Thread.sleep(150);
                assertEquals(publication, renderer.frame().publicationNanos());
                navigation.capture();
                navigation.look(40, 0);
                navigation.release();
                awaitSamples(
                        renderer,
                        frame -> frame.publicationNanos() != publication && frame.samples() == 1);
                assertEquals(96, renderer.frame().image().getWidth());
                SwingUtilities.invokeAndWait(() -> clickRefinement(panel[0]));
                assertTrue(renderer.refinement());
                awaitSamples(renderer, frame -> frame.samples() >= 4);
            } finally {
                if (panel[0] != null) {
                    SwingUtilities.invokeAndWait(panel[0]::close);
                }
            }
        }
    }

    private static void clickRefinement(GamePanel panel) {
        panel.dispatchEvent(
                new MouseEvent(
                        panel,
                        MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        250,
                        175,
                        1,
                        false,
                        MouseEvent.BUTTON1));
    }

    private static void awaitSamples(
            GameRenderer renderer, java.util.function.Predicate<GameRenderer.Frame> ready)
            throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while ((renderer.frame() == null || !ready.test(renderer.frame()))
                && renderer.error() == null
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(null, renderer.error());
        assertTrue(renderer.frame() != null && ready.test(renderer.frame()));
    }

    @Test
    void groupedControlsApplyCameraLightingAndDisplaySettings() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            var editor = new GameSettingsPanel[1];
            SwingUtilities.invokeAndWait(
                    () -> {
                        editor[0] = new GameSettingsPanel(navigation, renderer);
                        setNumber(editor[0], "Sample limit (0 = unlimited)", 8);
                        setNumber(editor[0], "Vertical FOV (degrees)", 75);
                        setNumber(editor[0], "Aperture radius (world units)", .1);
                        setNumber(editor[0], "Focus distance (world units)", 8);
                        setNumber(editor[0], "Flight speed (units/s)", 6);
                        setNumber(editor[0], "Mouse sensitivity (radians/pixel)", .005);
                        setNumber(editor[0], "Sun intensity", 2);
                        setNumber(editor[0], "Sun R", .5);
                        setNumber(editor[0], "Sky zenith B", .8);
                        setNumber(editor[0], "Min width (0 = auto)", 96);
                        assertEquals(96, renderer.settings().minimumWidth());
                        setNumber(editor[0], "Min height (0 = auto)", 0);
                        assertEquals(0, renderer.settings().minimumWidth());
                        assertEquals(0, renderer.settings().minimumHeight());
                        editor[0].refresh();
                    });
            assertEquals(.1f, navigation.view().camera().aperture());
            assertEquals(8f, navigation.view().camera().focus());
            assertTrue(Math.abs(navigation.view().camera().fov() - 75) < .001);
            assertEquals(6f, navigation.speed());
            assertEquals(.5f, renderer.lighting().sunColor().x());
            assertEquals(.8f, renderer.lighting().skyColors().zenith().z());
            renderer.lighting(renderer.lighting().withPreset(GameLighting.Preset.MORNING));
            assertEquals(2f, renderer.lighting().intensity());
            awaitSamples(renderer, frame -> frame.samples() == 8);
            long previous = renderer.frame().publicationNanos();
            int[] before = renderer.frame().image().getRGB(0, 0, 96, 64, null, 0, 96);
            SwingUtilities.invokeAndWait(() -> setNumber(editor[0], "Exposure multiplier", 2));
            awaitSamples(renderer, frame -> frame.publicationNanos() != previous);
            assertEquals(8L, renderer.frame().samples());
            int[] after = renderer.frame().image().getRGB(0, 0, 96, 64, null, 0, 96);
            assertFalse(java.util.Arrays.equals(before, after));
            renderer.refinement(false);
            awaitSamples(renderer, frame -> frame.samples() == 1);
            renderer.refinement(true);
            awaitSamples(renderer, frame -> frame.samples() == 8);
        }
    }

    private static void setNumber(java.awt.Container parent, String name, double value) {
        var spinner = (javax.swing.JSpinner) findControl(parent, name);
        assertTrue(spinner != null && spinner.isEnabled());
        spinner.setValue(value);
    }

    private static java.awt.Component findControl(java.awt.Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof java.awt.Container container) {
                var found = findControl(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void writesRefinedSunPresetAndSkyComparisonPreviews() throws Exception {
        var gallery = BlockWorld.gallery().snapshot();
        var view = new GameNavigation().view();
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSamplesPerBatch(8)
                        .withSampleTarget(32);
        for (var preset : GameLighting.Preset.values()) {
            write(
                    gallery,
                    new GameLighting(preset, true),
                    view,
                    settings,
                    "g3-" + preset.name().toLowerCase(java.util.Locale.ROOT) + ".png");
        }
        write(
                gallery,
                new GameLighting(GameLighting.Preset.NOON, false),
                view,
                settings,
                "g3-sky-off.png");
    }

    private static void write(
            WorldSnapshot gallery,
            GameLighting lighting,
            engine.RenderView view,
            RenderSettings settings,
            String name)
            throws Exception {
        var world =
                new WorldSnapshot(
                        0, gallery.instances(), List.of(), List.of(lighting.sun()), lighting.sky());
        try (var session = RenderEngine.openSession(world, view, settings)) {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                try (var image = session.acquireImage()) {
                    if (image != null && image.samples() >= 32) {
                        ImageIO.write(
                                GameRenderer.copyImage(image),
                                "png",
                                Path.of("benchmarks/game", name).toFile());
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("No refined preview: " + name);
        }
    }
}
