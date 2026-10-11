package engine;

import game.BlockWorld;
import game.GameLighting;
import game.GameNavigation;

import profiling.RuntimeMetrics;

import scenes.viewport.ScenePresets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Equal-work leaf/construction sweep; construction timing excludes geometry preparation. */
public final class BvhConstructionBenchmark {
    private record Construction(
            double millis, long allocated, long retained, int nodes, int depth) {}

    private record Configuration(boolean median, int objectLeaf, int primitiveLeaf) {}

    private record Group(PrimitiveBvh.Bounds[] bounds, boolean objects) {}

    private static boolean confirmation;
    private static String sceneFilter;

    private BvhConstructionBenchmark() {}

    public static void main(String[] args) {
        boolean sweep = args.length > 0 && args[0].equals("sweep");
        confirmation = !sweep;
        sceneFilter = args.length > 1 ? args[1] : null;
        System.out.println(
                "scene,round,policy,objectLeaf,primitiveLeaf,medianMs,p95Ms,buildMs,buildBytes,retainedBytes,nodes,depth,nodeVisits,boundsTests,primaryTests,continuationTests,shadowTests,workerBytes");
        var configurations = configurations(sweep);
        int rounds = confirmation ? 5 : 3;
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < configurations.size(); i++) {
                int index = round % 2 == 0 ? i : configurations.size() - 1 - i;
                var configuration = configurations.get(index);
                System.setProperty(
                        "engine.bvhMedianReference", Boolean.toString(configuration.median));
                System.setProperty(
                        "engine.bvhObjectLeafSize", Integer.toString(configuration.objectLeaf));
                System.setProperty(
                        "engine.bvhPrimitiveLeafSize",
                        Integer.toString(configuration.primitiveLeaf));
                measureScenes(round, configuration);
            }
        }
        System.clearProperty("engine.bvhMedianReference");
        System.clearProperty("engine.bvhObjectLeafSize");
        System.clearProperty("engine.bvhPrimitiveLeafSize");
    }

    private static ArrayList<Configuration> configurations(boolean sweep) {
        var configurations = new ArrayList<Configuration>();
        if (sweep) {
            for (boolean median : new boolean[] {true, false}) {
                for (int leaf : new int[] {1, 2, 4, 8}) {
                    configurations.add(new Configuration(median, leaf, leaf));
                }
            }
        } else {
            configurations.add(new Configuration(true, 4, 4));
            configurations.add(new Configuration(false, 4, 4));
            configurations.add(new Configuration(false, 4, 8));
        }
        return configurations;
    }

    private static void measureScenes(int round, Configuration configuration) {
        if (matches("landscape")) {
            var landscape = ObjectAccelerationTest.state(48);
            landscape.instances().addAll(BlockWorld.landscape().snapshot().instances());
            light(landscape);
            measure("landscape", round, configuration, landscape);
        }
        if (matches("blocks-1024")) {
            var blocks = ObjectAccelerationTest.state(48);
            for (int i = 0; i < 1024; i++) {
                blocks.instances().add(ObjectAccelerationTest.box("b" + i, i % 32 - 16, 0, i / 32));
            }
            light(blocks);
            measure("blocks-1024", round, configuration, blocks);
        }
        for (int detail : new int[] {4, 12, 32}) {
            if (!matches("mesh-" + detail)) {
                continue;
            }
            var mesh = ObjectAccelerationTest.state(48);
            ScenePresets.load(mesh, "mesh-room");
            var asset =
                    PolygonMesh.approximateSphere(new AnalyticSphere(math.Vec3.ZERO, 1), detail);
            for (int i = 0; i < 4; i++) {
                var instance = mesh.instances().get(i);
                mesh.instances()
                        .set(
                                i,
                                new SceneInstance(
                                        instance.name(),
                                        asset,
                                        instance.transform(),
                                        instance.material()));
            }
            measure("mesh-" + detail, round, configuration, mesh);
        }
    }

    private static boolean matches(String scene) {
        return sceneFilter == null || sceneFilter.equals(scene);
    }

    private static void light(ViewportState state) {
        state.camera(new GameNavigation().view().camera());
        state.lights().add(GameLighting.defaults().sun());
        state.sky(GameLighting.defaults().sky());
        state.pathDepth(2);
    }

    private static ArrayList<Group> constructionBounds(ViewportState state) {
        var groups = new ArrayList<Group>();
        var objectBounds = new PrimitiveBvh.Bounds[state.instances().size()];
        for (int i = 0; i < objectBounds.length; i++) {
            var instance = state.instances().get(i);
            var object = new PreparedObject(instance);
            objectBounds[i] = object.bounds;
            if (object.bvh != null) {
                var primitives = new PrimitiveBvh.Bounds[object.primitives.length];
                for (int j = 0; j < primitives.length; j++) {
                    primitives[j] = PrimitiveBvh.bounds(instance, object.geometry, j);
                }
                groups.add(new Group(primitives, false));
            }
        }
        if (objectBounds.length >= 32) {
            groups.add(new Group(objectBounds, true));
        }
        return groups;
    }

    private static Construction construction(ViewportState state, Configuration configuration) {
        var groups = constructionBounds(state);
        var trees = new PrimitiveBvh[groups.size()];
        var times = new double[21];
        var allocated = new long[21];
        for (int iteration = -5; iteration < times.length; iteration++) {
            long bytes = RuntimeMetrics.allocatedBytes();
            long start = System.nanoTime();
            for (int i = 0; i < trees.length; i++) {
                var group = groups.get(i);
                int leaf = group.objects ? configuration.objectLeaf : configuration.primitiveLeaf;
                trees[i] = new PrimitiveBvh(group.bounds, !configuration.median, leaf);
            }
            long elapsed = System.nanoTime() - start;
            long delta = RuntimeMetrics.delta(bytes, RuntimeMetrics.allocatedBytes());
            if (iteration >= 0) {
                times[iteration] = elapsed / 1e6;
                allocated[iteration] = delta;
            }
        }
        int nodes = 0;
        int depth = 0;
        long retained = 0;
        for (var tree : trees) {
            nodes += tree.nodes.length;
            depth = Math.max(depth, tree.maximumDepth);
            long bytes = BvhMemoryAgent.retainedBytes(tree);
            retained = bytes < 0 || retained < 0 ? -1 : retained + bytes;
        }
        Arrays.sort(times);
        Arrays.sort(allocated);
        return new Construction(times[10], allocated[10], retained, nodes, depth);
    }

    private static void measure(
            String scene, int round, Configuration configuration, ViewportState state) {
        state.workers(1);
        state.samplesPerFrame(1);
        state.sampleTarget(10000);
        var construction = construction(state, configuration);
        try (var tracer = new DirectRgbTracer(state)) {
            for (int i = 0; i < (confirmation ? 100 : 40); i++) {
                tracer.trace();
            }
            var times = new double[confirmation ? 200 : 60];
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
                    "%s,%d,%s,%d,%d,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    scene,
                    round,
                    configuration.median ? "median" : "sah",
                    configuration.objectLeaf,
                    configuration.primitiveLeaf,
                    times[times.length / 2],
                    times[(int) Math.ceil(times.length * .95) - 1],
                    construction.millis,
                    construction.allocated,
                    construction.retained,
                    construction.nodes,
                    construction.depth,
                    visits,
                    bounds,
                    primary,
                    continuation,
                    shadow,
                    bytes);
        }
    }
}
