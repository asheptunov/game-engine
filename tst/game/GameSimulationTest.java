package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.LightingContinuity;

import harness.SuiteRunner;
import harness.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class GameSimulationTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void elapsedTimeIsIndependentOfStepCountAndSnapshotReads() {
        try (var slow = simulation();
                var fast = simulation()) {
            slow.running(true);
            fast.running(true);
            var frozen = slow.state();
            for (int i = 0; i < 90; i++) {
                slow.advance(1. / 30, true);
            }
            for (int i = 0; i < 360; i++) {
                fast.advance(1. / 120, true);
            }
            assertTrue(Math.abs(slow.state().hour() - fast.state().hour()) < 1e-10);
            assertTrue(Math.abs(slow.state().hour() - 9.12) < 1e-10);
            assertEquals(9., frozen.hour());
            var beforeReads = slow.state();
            for (int i = 0; i < 1000; i++) {
                slow.state().atmosphericLighting(engine.Atmosphere.Parameters.clearAir());
            }
            assertEquals(beforeReads, slow.state());
            slow.advance(20, false);
            assertEquals(beforeReads, slow.state());
            slow.advance(.5, true);
            assertTrue(slow.state().hour() > beforeReads.hour());
            assertTrue(slow.state().continuity().follows(beforeReads.continuity()));
            var previous = slow.state().continuity();
            slow.hour(slow.state().hour());
            assertTrue(!slow.state().continuity().follows(previous));
        }
    }

    private static GameSimulation simulation() {
        return new GameSimulation(
                new GameNavigation(),
                () -> null,
                error -> {
                    throw new AssertionError(error);
                });
    }

    @Test
    void stalledImageConversionCannotStopSunAndInactiveGameExcludesElapsedTime() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(64, 64);
        navigation.active(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var renderer =
                new GameRenderer(
                        navigation,
                        BlockWorld.gallery(),
                        "cpu",
                        (image, exposure) -> {
                            entered.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Conversion gate timed out");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(error);
                            }
                            return GameRenderer.copyImage(image, exposure);
                        })) {
            try {
                renderer.dayDuration(30);
                renderer.dayCycle(true);
                navigation.active(true);
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                double before = renderer.timeOfDay();
                Thread.sleep(250);
                assertTrue(renderer.timeOfDay() - before > .12);
                assertEquals(null, renderer.frame());
                navigation.active(false);
                Thread.sleep(50);
                double paused = renderer.timeOfDay();
                Thread.sleep(100);
                assertEquals(paused, renderer.timeOfDay());
                release.countDown();
                navigation.active(true);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (renderer.frame() == null
                        && renderer.error() == null
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(null, renderer.error());
                assertTrue(renderer.frame() != null);
                assertTrue(renderer.frame().context().world().lightingContinuity().timeline() != 0);
                renderer.configure(s -> s.withPaused(true));
                before = renderer.timeOfDay();
                Thread.sleep(150);
                assertTrue(renderer.timeOfDay() - before > .06);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void capturedLightingTimelineRoundTripsAndOlderCapturesDefaultToNone() throws Exception {
        var file = Path.of("out/game-check/sun-world.properties");
        Files.createDirectories(file.getParent());
        var world =
                new GameCapture.World(
                        0, 4, engine.Sky.BLACK, List.of(), new LightingContinuity(7, 3, 8.5));
        CaptureCodec.save(file, world);
        assertEquals(world, CaptureCodec.load(file, GameCapture.World.class));
        String old =
                Files.readString(file)
                        .lines()
                        .filter(line -> !line.startsWith("data.lightingContinuity"))
                        .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(file, old);
        assertEquals(
                LightingContinuity.NONE,
                CaptureCodec.load(file, GameCapture.World.class).lightingContinuity());
    }
}
