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
