package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertSame;
import static harness.Assertions.assertTrue;

import engine.BoxGeometry;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.WorldSnapshot;

import harness.SuiteRunner;
import harness.Test;

import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class GameLandscapeTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void worldIsDeterministicUniqueAndSharesAssets() {
        var blocks = BlockWorld.landscape();
        assertEquals(blocks.blocks(), BlockWorld.landscape().blocks());
        assertEquals(313, blocks.blocks().size());
        var coordinates = new HashSet<String>();
        var materials = new HashMap<BlockWorld.Type, engine.Material>();
        var world = blocks.snapshot();
        for (int index = 0; index < blocks.blocks().size(); index++) {
            var block = blocks.blocks().get(index);
            assertTrue(coordinates.add(block.x() + "," + block.y() + "," + block.z()));
            var instance = world.instances().get(index);
            assertSame(BoxGeometry.UNIT, instance.geometry());
            var previous = materials.putIfAbsent(block.type(), instance.material());
            if (previous != null) {
                assertSame(previous, instance.material());
            }
        }
        assertEquals(5, materials.size());
        assertEquals(
                144L,
                blocks.blocks().stream()
                        .filter(block -> block.type() == BlockWorld.Type.GRASS)
                        .count());
        assertEquals(128, BlockWorld.gallery().blocks().size());
    }

    @Test
    void routeUsesElapsedTimeUnwrappedAnglesAndExactHold() throws Exception {
        var route = CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        var navigation = new GameNavigation();
        route.apply(navigation, 0);
        assertEquals(new GameNavigation().view(), navigation.view());
        var middle = route.at(4);
        assertTrue(Math.abs(middle.position().x() + .5) < .0001);
        assertTrue(Math.abs(middle.yaw() + .6) < .0001);
        assertTrue(Math.abs(route.at(20.5).yaw() - Math.PI / 2) < .0001);
        route.apply(navigation, 25);
        var finalView = navigation.view();
        for (double time = 25; time <= 30; time += .013) {
            route.apply(navigation, time);
            assertEquals(finalView, navigation.view());
        }
    }

    @Test
    void fullLandscapePreviewsRecoverRefineAndToggleWithoutCapture() throws Exception {
        var navigation = new GameNavigation();
        var route = CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        try (var renderer = new GameRenderer(navigation, BlockWorld.landscape())) {
            long start = System.nanoTime();
            long seen = -1;
            int fresh = 0;
            boolean reduced = false;
            while (System.nanoTime() - start < 3_000_000_000L) {
                route.apply(navigation, (System.nanoTime() - start) / 1e9);
                var frame = renderer.frame();
                if (frame != null && frame.publicationNanos() != seen) {
                    seen = frame.publicationNanos();
                    fresh++;
                    reduced |= frame.image().getWidth() < 320;
                }
                assertEquals(null, renderer.error());
                Thread.sleep(10);
            }
            assertTrue(fresh >= 3);
            assertTrue(reduced);
            route.apply(navigation, 25);
            awaitRefinement(renderer, navigation);
            var panel = new GamePanel[1];
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel[0] = new GamePanel(navigation, renderer);
                        panel[0].dispatchEvent(
                                new MouseEvent(
                                        panel[0],
                                        MouseEvent.MOUSE_PRESSED,
                                        1,
                                        0,
                                        40,
                                        175,
                                        1,
                                        false,
                                        MouseEvent.BUTTON1));
                    });
            assertTrue(!renderer.adaptive());
            assertTrue(!navigation.captured());
            assertEquals(2, renderer.settings().pathDepth());
            renderer.adaptive(true);
            navigation.active(false);
            Thread.sleep(100);
            assertTrue(renderer.suspended());
            navigation.active(true);
            navigation.resize(600, 800);
            navigation.reset();
            awaitRefinement(renderer, navigation);
            SwingUtilities.invokeAndWait(panel[0]::close);
        }
    }

    private static void awaitRefinement(GameRenderer renderer, GameNavigation navigation)
            throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            assertEquals(null, renderer.error());
            var frame = renderer.frame();
            var view = navigation.view();
            if (frame != null
                    && frame.image().getWidth() == view.width()
                    && frame.image().getHeight() == view.height()
                    && frame.camera().equals(view.camera())
                    && frame.samples() >= 4) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Requested grid and stationary refinement did not return");
    }

    @Test
    void seededLandscapeMatchesBruteAndWritesPreview() throws Exception {
        var source = BlockWorld.landscape().snapshot();
        var lighting = GameLighting.defaults();
        var world =
                new WorldSnapshot(
                        0,
                        source.instances(),
                        source.legacyObjects(),
                        List.of(lighting.sun()),
                        lighting.sky());
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSampleTarget(8)
                        .withSamplesPerBatch(8);
        int[] accelerated = render(world, settings);
        assertEquals(accelerated, render(world, settings.withAcceleration(false)));
    }

    private static int[] render(WorldSnapshot world, RenderSettings settings) throws Exception {
        try (var session = RenderEngine.openSession(world, new GameNavigation().view(), settings)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                try (var image = session.acquireImage()) {
                    if (image != null && image.samples() == 8) {
                        var display = GameRenderer.copyImage(image);
                        if (settings.acceleration()) {
                            ImageIO.write(
                                    display,
                                    "png",
                                    Path.of("benchmarks/game/g4-landscape.png").toFile());
                        }
                        return display.getRGB(0, 0, 320, 200, null, 0, 320);
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("No landscape preview");
        }
    }
}
