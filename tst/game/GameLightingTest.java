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
    void autofocusTracksCenterRetainsSkyAndStopsForManualAndSuspension() throws Exception {
        var navigation = new GameNavigation();
        navigation.pose(new math.Vec3(0, 0, -6), 0, 0);
        navigation.optics(.1f, 2);
        var world = new BlockWorld(List.of(new BlockWorld.Block(0, 0, 0, BlockWorld.Type.STONE)));
        try (var autofocus = new GameAutofocus(navigation, world.snapshot())) {
            navigation.autofocus(true);
            awaitFocus(navigation, 5.5f);
            navigation.pose(new math.Vec3(0, 0, -3), 0, 0);
            awaitFocus(navigation, 2.5f);
            navigation.pose(new math.Vec3(0, 0, -3), Math.PI, 0);
            Thread.sleep(200);
            assertEquals(2.5f, navigation.focus());
            navigation.manualFocus(7);
            navigation.pose(new math.Vec3(0, 0, -3), 0, 0);
            Thread.sleep(200);
            assertFalse(navigation.autofocus());
            assertEquals(7f, navigation.focus());
            navigation.active(false);
            navigation.autofocus(true);
            Thread.sleep(200);
            assertEquals(7f, navigation.focus());
            navigation.active(true);
            awaitFocus(navigation, 2.5f);
        }
    }

    @Test
    void autofocusRejectsResultsFromOldModeAndOldView() {
        var navigation = new GameNavigation();
        navigation.pose(new math.Vec3(0, 0, -6), 0, 0);
        navigation.autofocus(true);
        var world =
                new BlockWorld(List.of(new BlockWorld.Block(0, 0, 0, BlockWorld.Type.STONE)))
                        .snapshot();
        var scene = new engine.CameraFocus.Scene(world.instances(), world.legacyObjects(), 0);
        var result =
                new engine.CameraFocus()
                        .resolve(
                                engine.FocusTargetSource.CENTER,
                                navigation.view().camera(),
                                scene,
                                navigation.focusRevision(),
                                System.nanoTime(),
                                System::nanoTime);
        assertEquals(null, result.error());
        navigation.pose(new math.Vec3(0, 0, -6), Math.PI, 0);
        assertFalse(navigation.acceptFocus(result, result.distance()));
        navigation.pose(new math.Vec3(0, 0, -6), 0, 0);
        navigation.autofocus(false);
        navigation.autofocus(true);
        assertFalse(navigation.acceptFocus(result, result.distance()));
        assertEquals(5f, navigation.focus());
    }

    private static void awaitFocus(GameNavigation navigation, float expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (navigation.focus() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, navigation.focus());
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
                            assertEquals(7.0, renderer.timeOfDay());
                            assertFalse(navigation.captured());
                            click(panel[0], 240);
                            assertEquals(17.0, renderer.timeOfDay());
                            click(panel[0], 345);
                            assertFalse(renderer.lighting().skyEnabled());
                            var lighting = renderer.lighting();
                            navigation.reset();
                            assertEquals(lighting, renderer.lighting());
                            click(panel[0], 140);
                            assertEquals(12.0, renderer.timeOfDay());
                            assertTrue(renderer.lighting().skyEnabled());
                            renderer.atmosphereEnabled(true);
                            renderer.dayCycle(true);
                            click(panel[0], 30);
                            assertEquals(7.0, renderer.timeOfDay());
                            assertFalse(renderer.dayCycle());
                            click(panel[0], 140);
                            assertEquals(12.0, renderer.timeOfDay());
                            click(panel[0], 240);
                            assertEquals(17.0, renderer.timeOfDay());
                            renderer.atmosphereEnabled(false);
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
                        setNumber(editor[0], "Movement speed (units/s)", 6);
                        var walking =
                                (javax.swing.JCheckBox)
                                        findControl(editor[0], "Walk with gravity (off = fly)");
                        walking.doClick();
                        assertTrue(navigation.walking());
                        walking.doClick();
                        assertTrue(!navigation.walking());
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
            var expectedLights = java.util.List.of(renderer.lighting().sun());
            awaitSamples(
                    renderer,
                    frame ->
                            frame.samples() == 8
                                    && frame.context() != null
                                    && frame.context().world().lights().equals(expectedLights));
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

    @Test
    void sunPresetButtonsSelectSameClockTimesWithEitherSky() throws Exception {
        var navigation = new GameNavigation();
        navigation.active(false);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        var editor = new GameSettingsPanel(navigation, renderer);
                        renderer.atmospherePreset(true);
                        var atmosphere = renderer.atmosphere();
                        var camera = navigation.view().camera();
                        int[] hours = {7, 12, 17};
                        int index = 0;
                        for (var preset : GameLighting.Preset.values()) {
                            renderer.dayCycle(true);
                            ((javax.swing.JButton) findControl(editor, preset.name())).doClick();
                            assertEquals((double) hours[index++], renderer.timeOfDay());
                            assertFalse(renderer.dayCycle());
                            assertTrue(renderer.atmosphereEnabled());
                            assertEquals(atmosphere, renderer.atmosphere());
                            assertEquals(camera, navigation.view().camera());
                            var clock =
                                    (javax.swing.JSpinner)
                                            findControl(editor, "Time of day (hours)");
                            assertEquals(
                                    renderer.timeOfDay(),
                                    ((Number) clock.getValue()).doubleValue());
                        }
                        renderer.atmosphereEnabled(false);
                        index = 0;
                        for (var preset : GameLighting.Preset.values()) {
                            renderer.dayCycle(true);
                            var expected = new DayNightCycle();
                            expected.hour(hours[index++]);
                            ((javax.swing.JButton) findControl(editor, preset.name())).doClick();
                            assertEquals(expected.lighting(), renderer.lighting());
                            assertEquals(expected.hour(), renderer.timeOfDay());
                            assertFalse(renderer.dayCycle());
                            assertEquals(atmosphere, renderer.atmosphere());
                            assertEquals(camera, navigation.view().camera());
                            var clock =
                                    (javax.swing.JSpinner)
                                            findControl(editor, "Time of day (hours)");
                            assertEquals(
                                    renderer.timeOfDay(),
                                    ((Number) clock.getValue()).doubleValue());
                        }
                    });
        }
    }

    @Test
    void fogControlsPreserveOpticsAndResetOnlyLocalAir() throws Exception {
        var navigation = new GameNavigation();
        navigation.active(false);
        try (var renderer = new GameRenderer(navigation, BlockWorld.gallery())) {
            renderer.atmospherePreset(false);
            var atmosphere = renderer.atmosphere();
            var camera = navigation.view().camera();
            var settings = renderer.settings();
            SwingUtilities.invokeAndWait(
                    () -> {
                        var editor = new GameSettingsPanel(navigation, renderer);
                        ((javax.swing.JButton) findControl(editor, "Ruins mist (07:30)")).doClick();
                        assertTrue(renderer.fogEnabled());
                        assertEquals(7.5, renderer.timeOfDay());
                        assertFalse(renderer.dayCycle());
                        setNumber(editor, "Fog density scale", 2);
                        assertEquals(2f, renderer.fogRegion(0).density());
                        setNumber(editor, "Edit region (1 or 2)", 2);
                        setNumber(editor, "Fog density scale", .5);
                        assertEquals(.5f, renderer.fogRegion(1).density());
                        ((javax.swing.JCheckBox) findControl(editor, "Second overlapping region"))
                                .doClick();
                        ((javax.swing.JCheckBox) findControl(editor, "Reverse region order"))
                                .doClick();
                        ((javax.swing.JCheckBox) findControl(editor, "Close sunlight gap"))
                                .doClick();
                        assertTrue(
                                renderer.fogOverlap()
                                        && renderer.fogReverse()
                                        && renderer.fogClosed());
                        setNumber(editor, "Screen shift Z (world units)", 3);
                        assertEquals(3f, renderer.fogShift());
                        ((javax.swing.JButton) findControl(editor, "Reset local fog")).doClick();
                        assertFalse(renderer.fogEnabled());
                        assertFalse(renderer.fogOverlap());
                        assertEquals(RuinsMist.region(0), renderer.fogRegion(0));
                        assertEquals(7.5, renderer.timeOfDay());
                        assertEquals(atmosphere, renderer.atmosphere());
                        assertTrue(renderer.atmosphereEnabled());
                        assertEquals(camera, navigation.view().camera());
                        assertEquals(settings, renderer.settings());
                    });
        }
    }

    @Test
    void standardResolutionSelectorPreservesCameraAndCustomDimensions() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, BlockWorld.landscape())) {
            renderer.configure(settings -> settings.withPaused(true));
            SwingUtilities.invokeAndWait(
                    () -> {
                        var editor = new GameSettingsPanel(navigation, renderer);
                        var selector =
                                (javax.swing.JComboBox<?>) findControl(editor, "Render resolution");
                        assertEquals("Custom", selector.getSelectedItem().toString());
                        var camera = navigation.view().camera();
                        int[][] sizes = {
                            {640, 360}, {854, 480}, {960, 540}, {1280, 720}, {1600, 900}
                        };
                        for (int i = 0; i < sizes.length; i++) {
                            selector.setSelectedIndex(i);
                            assertEquals(sizes[i][0], navigation.view().width());
                            assertEquals(sizes[i][1], navigation.view().height());
                            assertEquals(camera, navigation.view().camera());
                        }
                        selector.setSelectedIndex(5);
                        editor.refresh();
                        assertEquals("Custom", selector.getSelectedItem().toString());
                        assertEquals(1600, navigation.view().width());
                        setNumber(editor, "Width (pixels)", 1008);
                        setNumber(editor, "Height (pixels)", 672);
                        assertEquals(1008, navigation.view().width());
                        assertEquals(672, navigation.view().height());
                        assertEquals(camera, navigation.view().camera());
                        selector.setSelectedIndex(3);
                        editor.refresh();
                        assertEquals(1280, navigation.view().width());
                        assertEquals(720, navigation.view().height());
                        assertEquals(3, selector.getSelectedIndex());
                    });
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
