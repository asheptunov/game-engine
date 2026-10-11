package engine;

import game.BlockWorld;
import game.GameLighting;
import game.GameNavigation;

import scenes.viewport.ScenePresets;

import java.util.Arrays;
import java.util.Locale;

/** Equal-work, single-worker comparison of object trees against the old bounded object scan. */
public final class ObjectAccelerationBenchmark {
    private ObjectAccelerationBenchmark() {}

    public static void main(String[] args) {
        System.out.println(
                "scene,objects,mode,medianMs,p95Ms,buildMedianMs,nodes,primaryTests,continuationTests,shadowTests");
        for (int count : new int[] {1, 4, 8, 16, 32, 64, 128, 313, 512, 1024}) {
            var state = ObjectAccelerationTest.state(64);
            int side = (int) Math.ceil(Math.sqrt(count));
            for (int i = 0; i < count; i++) {
                state.instances()
                        .add(
                                ObjectAccelerationTest.box(
                                        "b" + i, i % side - side / 2f, 0, i / side));
            }
            state.camera(new GameNavigation().view().camera());
            light(state);
            compare("blocks-" + count, state);
        }
        var game = ObjectAccelerationTest.state(64);
        game.instances().addAll(BlockWorld.landscape().snapshot().instances());
        game.camera(new GameNavigation().view().camera());
        light(game);
        compare("landscape", game);
        for (String preset : new String[] {"playground", "mesh-room", "glass", "volume-room"}) {
            var state = ObjectAccelerationTest.state(64);
            ScenePresets.load(state, preset);
            compare(preset, state);
        }
    }

    private static void light(ViewportState state) {
        state.lights().add(GameLighting.defaults().sun());
        state.sky(GameLighting.defaults().sky());
        state.pathDepth(2);
    }

    private static void compare(String name, ViewportState state) {
        state.workers(1);
        state.samplesPerFrame(1);
        state.sampleTarget(10000);
        var bounds =
                state.instances().stream()
                        .map(PreparedObject::new)
                        .map(o -> o.bounds)
                        .toArray(PrimitiveBvh.Bounds[]::new);
        var builds = new double[101];
        int nodes = 0;
        for (int i = 0; i < builds.length; i++) {
            long start = System.nanoTime();
            var tree = new PrimitiveBvh(bounds);
            builds[i] = (System.nanoTime() - start) / 1e6;
            nodes = tree.nodes.length;
        }
        Arrays.sort(builds);
        // Alternate order in repeated rounds to reduce warmup/order effects.
        for (int round = 0; round < 3; round++) {
            for (int mode = 0; mode < 2; mode++) {
                boolean tree = (mode + round) % 2 == 0;
                measure(name, state, tree, builds[50], nodes);
            }
        }
    }

    private static void measure(
            String name, ViewportState state, boolean tree, double buildMillis, int nodes) {
        var copy = state.renderSnapshot();
        try (var tracer = new DirectRgbTracer(copy, tree ? 1 : Integer.MAX_VALUE)) {
            for (int i = 0; i < 40; i++) {
                tracer.trace();
            }
            var times = new double[60];
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                tracer.trace();
                times[i] = (System.nanoTime() - start) / 1e6;
            }
            Arrays.sort(times);
            System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%s,%.6f,%.6f,%.6f,%d,%d,%d,%d%n",
                    name,
                    state.instances().size(),
                    tree ? "tree" : "flat",
                    times[30],
                    times[56],
                    tree ? buildMillis : 0,
                    tree ? nodes : 0,
                    tracer.primaryTests,
                    tracer.continuationTests,
                    tracer.shadowTests);
        }
    }
}
