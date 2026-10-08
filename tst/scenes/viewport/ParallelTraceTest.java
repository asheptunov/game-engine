package engine;

import static harness.Assertions.*;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import scenes.viewport.*;

public class ParallelTraceTest {
    private ViewportState state(String preset, int workers, int tile) {
        var state =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        67,
                        73);
        ScenePresets.load(state, preset);
        state.workers(workers);
        state.tileSize(tile);
        state.seed(37);
        state.sampleTarget(3);
        return state;
    }

    private void same(float[][][] a, float[][][] b) {
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < a[c].length; y++)
                for (int x = 0; x < a[c][y].length; x++)
                    if (Float.floatToIntBits(a[c][y][x]) != Float.floatToIntBits(b[c][y][x]))
                        throw new AssertionError("Different pixel at " + c + "," + x + "," + y);
    }

    @Test
    void tileAndWorkerSchedulingPreserveEveryTransportPreset() {
        int max = Math.min(4, Runtime.getRuntime().availableProcessors());
        for (var preset :
                new String[] {
                    "triangle",
                    "playground",
                    "bounce-room",
                    "glass",
                    "glass-inside",
                    "rough-room",
                    "mesh-room",
                    "volume-room"
                }) {
            var reference =
                    state(preset, 1, 256); // One row-major tile, matching the original loop order.
            try (var a = new DirectRgbTracer(reference)) {
                for (int i = 0; i < 3; i++) a.trace();
                for (int workers : new int[] {1, max})
                    for (int tile : new int[] {16, 32}) {
                        var candidate = state(preset, workers, tile);
                        try (var b = new DirectRgbTracer(candidate)) {
                            for (int i = 0; i < 3; i++) b.trace();
                            same(a.radianceBuffer(), b.radianceBuffer());
                            assertEquals(a.primaryRays, b.primaryRays);
                            assertEquals(a.shadowRays, b.shadowRays);
                            assertEquals(a.primaryTests, b.primaryTests);
                            assertEquals(a.shadowTests, b.shadowTests);
                            assertEquals(a.continuationTests, b.continuationTests);
                            assertEquals(a.volumeEvents, b.volumeEvents);
                            assertEquals(a.areaLightSamples, b.areaLightSamples);
                            assertEquals(a.dielectricTransmissions, b.dielectricTransmissions);
                        }
                    }
            }
        }
    }

    @Test
    void settingsAndLifecycleKeepCompletePasses() {
        var s = state("glass", Math.min(4, Runtime.getRuntime().availableProcessors()), 16);
        try (var tracer = new DirectRgbTracer(s)) {
            tracer.trace();
            s.workers(1);
            s.tileSize(32);
            tracer.trace();
            assertEquals(2L, s.accumulatedSamples());
            s.paused(true);
            tracer.trace();
            assertEquals(0, tracer.primaryRays);
            assertEquals(2L, s.accumulatedSamples());
            s.restart();
            tracer.trace();
            assertEquals(0L, s.accumulatedSamples());
            s.paused(false);
            s.resolution(65, 71);
            tracer.trace();
            assertEquals(65 * 71, tracer.primaryRays);
            ScenePresets.load(s, "volume-room");
            tracer.trace();
            assertEquals(1L, s.accumulatedSamples());
            assertTrue(tracer.profile.allocatedBytes() >= -1);
            assertTrue(tracer.profile.cpuNanos() >= -1);
        }
        var closed = new DirectRgbTracer(s);
        closed.close();
        boolean rejected = false;
        try {
            closed.trace();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void interruptedCoordinatorDrainsWorkersBeforeRestart() {
        if (Runtime.getRuntime().availableProcessors() < 2) return;
        var s = state("glass", 2, 16);
        try (var tracer = new DirectRgbTracer(s)) {
            tracer.trace();
            Thread.currentThread().interrupt();
            boolean rejected = false;
            try {
                tracer.trace();
            } catch (IllegalStateException expected) {
                rejected = true;
            } finally {
                assertTrue(Thread.interrupted());
            }
            assertTrue(rejected);
            assertEquals(1L, s.accumulatedSamples());
            tracer.trace();
            assertEquals(1L, s.accumulatedSamples());
            var fresh = state("glass", 1, 256);
            try (var reference = new DirectRgbTracer(fresh)) {
                reference.trace();
                same(reference.radianceBuffer(), tracer.radianceBuffer());
            }
        }
    }

    @Test
    void tracingCommandsValidateAndRetainAccumulation() {
        var s = state("playground", 1, 32);
        var command = new ViewportCommand(s);
        try (var tracer = new DirectRgbTracer(s)) {
            tracer.trace();
            assertTrue(command.run("view", "tile", "16").isSuccess());
            assertTrue(command.run("view", "workers", "1").isSuccess());
            tracer.trace();
            assertEquals(2L, s.accumulatedSamples());
            assertTrue(!command.run("view", "workers", "0").isSuccess());
            assertTrue(!command.run("view", "tile", "257").isSuccess());
            assertTrue(command.help("workers").isSuccess());
            assertTrue(command.help("tile").isSuccess());
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
