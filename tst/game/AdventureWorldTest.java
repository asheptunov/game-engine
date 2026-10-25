package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.PolygonMesh;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.WorldSnapshot;

import harness.SuiteRunner;
import harness.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import javax.imageio.ImageIO;

public final class AdventureWorldTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            previews(args[0]);
        } else {
            SuiteRunner.runThis();
        }
    }

    @Test
    void deterministicMeshWorldHasUniqueNamesAndBroadTerrain() {
        var world = AdventureWorld.create();
        assertEquals(world, AdventureWorld.create());
        assertTrue(world.instances().size() > 200);
        var names = new HashSet<String>();
        for (var instance : world.instances()) {
            assertTrue(names.add(instance.name()));
            assertTrue(instance.geometry() instanceof PolygonMesh);
        }
        var terrain = (PolygonMesh) world.instances().getFirst().geometry();
        assertEquals(200, terrain.faces().size());
        assertEquals(-80f, terrain.editableVertices().getFirst().position().x());
        assertTrue(AdventureWorld.height(80, 0) > AdventureWorld.height(0, 0) + 5);
    }

    @Test
    void clockWrapsPausesAndProducesDayAndNight() {
        var clock = new DayNightCycle();
        clock.hour(23);
        clock.advance(25);
        assertEquals(23., clock.hour());
        clock.running(true);
        clock.advance(25);
        assertEquals(0., clock.hour());
        clock.hour(12);
        var noon = clock.lighting();
        clock.hour(0);
        var night = clock.lighting();
        assertTrue(noon.intensity() > night.intensity() * 20);
        assertTrue(noon.sky().zenith().z() > night.sky().zenith().z() * 20);
        clock.hour(24);
        assertEquals(night, clock.lighting());
    }

    @Test
    void cyclePublishesAndSuspendsWithoutStarvingImages() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(navigation, AdventureWorld.create(), "cpu")) {
            renderer.dayDuration(30);
            renderer.dayCycle(true);
            long deadline = System.nanoTime() + 20_000_000_000L;
            var seen = new HashSet<Long>();
            while (seen.size() < 4 && System.nanoTime() < deadline) {
                var frame = renderer.frame();
                if (frame != null) {
                    seen.add(frame.generation());
                }
                assertEquals(null, renderer.error());
                Thread.sleep(20);
            }
            assertTrue(seen.size() >= 4);
            navigation.active(false);
            Thread.sleep(100);
            double paused = renderer.timeOfDay();
            Thread.sleep(100);
            assertEquals(paused, renderer.timeOfDay());
            renderer.lighting(GameLighting.defaults());
            assertTrue(!renderer.dayCycle());
        }
    }

    @Test
    void rendersDayAndNightPreviews() throws Exception {
        previews("cpu");
    }

    private static void previews(String backend) throws Exception {
        var source = AdventureWorld.create();
        var clock = new DayNightCycle();
        var navigation = new GameNavigation();
        navigation.fixedResolution(480, 300);
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSampleTarget(8)
                        .withSamplesPerBatch(8);
        Files.createDirectories(Path.of("out/game"));
        for (int hour : new int[] {9, 18, 0}) {
            clock.hour(hour);
            var light = clock.lighting();
            var world =
                    new WorldSnapshot(
                            hour, source.instances(), List.of(), List.of(light.sun()), light.sky());
            try (var session =
                    RenderEngine.openSession(world, navigation.view(), settings, backend)) {
                long deadline = System.nanoTime() + 60_000_000_000L;
                boolean saved = false;
                while (System.nanoTime() < deadline) {
                    session.request();
                    try (var image = session.acquireImage()) {
                        if (image != null && image.samples() >= 8) {
                            ImageIO.write(
                                    GameRenderer.copyImage(image),
                                    "png",
                                    Path.of("out/game/world-" + backend + "-" + hour + ".png")
                                            .toFile());
                            saved = true;
                            break;
                        }
                    }
                    Thread.sleep(10);
                }
                assertTrue(saved);
            }
        }
    }
}
