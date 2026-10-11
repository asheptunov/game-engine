package engine;

import game.BlockWorld;
import game.GameLighting;
import game.GameNavigation;

import scenes.viewport.ScenePresets;

import java.util.Arrays;
import java.util.Locale;

/** Matched one-sample, single-worker traversal cost; run separate JVMs for each traversal mode. */
public final class BvhTraversalBenchmark {
    private BvhTraversalBenchmark() {}

    public static void main(String[] args) {
        System.out.println(
                "scene,round,mode,medianMs,p95Ms,nodeVisits,boundsTests,primaryTests,continuationTests,shadowTests,workerBytes");
        for (int round = 0; round < 5; round++) {
            for (int mode = 0; mode < 2; mode++) {
                boolean escape = (round + mode) % 2 == 0;
                System.setProperty("engine.bvhEscapeReference", Boolean.toString(escape));
                measureScenes(round);
            }
        }
        System.clearProperty("engine.bvhEscapeReference");
    }

    private static void measureScenes(int round) {
        var landscape = ObjectAccelerationTest.state(64);
        landscape.instances().addAll(BlockWorld.landscape().snapshot().instances());
        light(landscape);
        measure("landscape", round, landscape);
        var blocks = ObjectAccelerationTest.state(64);
        for (int i = 0; i < 1024; i++) {
            blocks.instances().add(ObjectAccelerationTest.box("b" + i, i % 32 - 16, 0, i / 32));
        }
        light(blocks);
        measure("blocks-1024", round, blocks);
        var mesh = ObjectAccelerationTest.state(64);
        ScenePresets.load(mesh, "mesh-room");
        measure("mesh-room", round, mesh);
    }

    private static void light(ViewportState state) {
        state.camera(new GameNavigation().view().camera());
        state.lights().add(GameLighting.defaults().sun());
        state.sky(GameLighting.defaults().sky());
        state.pathDepth(2);
    }

    private static void measure(String scene, int round, ViewportState state) {
        state.workers(1);
        state.samplesPerFrame(1);
        state.sampleTarget(10000);
        try (var tracer = new DirectRgbTracer(state)) {
            for (int i = 0; i < 100; i++) {
                tracer.trace();
            }
            var times = new double[200];
            long visits = 0;
            long bounds = 0;
            long primary = 0;
            long continuation = 0;
            long shadow = 0;
            long bytes = 0;
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                tracer.trace();
                times[i] = (System.nanoTime() - start) / 1e6;
                visits += tracer.hierarchyNodeVisits();
                bounds += tracer.hierarchyBoundsTests();
                primary += tracer.primaryTests;
                continuation += tracer.continuationTests;
                shadow += tracer.shadowTests;
                bytes += tracer.workerAllocatedBytes;
            }
            Arrays.sort(times);
            System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%s,%.6f,%.6f,%d,%d,%d,%d,%d,%d%n",
                    scene,
                    round,
                    Boolean.getBoolean("engine.bvhEscapeReference") ? "escape" : "near",
                    times[100],
                    times[189],
                    visits,
                    bounds,
                    primary,
                    continuation,
                    shadow,
                    bytes);
        }
    }
}
