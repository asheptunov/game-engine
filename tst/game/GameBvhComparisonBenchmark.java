package game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Alternating fixed-grid replays in one JVM, with only one active game session at a time. */
public final class GameBvhComparisonBenchmark {
    private record Run(
            String policy, GameNavigation navigation, GameRenderer renderer, Path output) {}

    private GameBvhComparisonBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "out/game-bvh-comparison");
        Files.createDirectories(output);
        var route = CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        var runs = new ArrayList<Run>();
        var summary =
                new StringBuilder(
                        "policy,run,freshMoving,p95Millis,maxMillis,restoredSeconds,firstRestoredSpp,finalSpp,passed\n");
        try {
            for (boolean median : new boolean[] {true, false}) {
                runs.add(warmup(output, route, median));
            }
            for (int round = 1; round <= 3; round++) {
                for (int mode = 0; mode < 2; mode++) {
                    var run = runs.get((mode + round + 1) % 2);
                    run.navigation.active(true);
                    var result =
                            GameRouteBenchmark.replay(
                                    run.renderer, run.navigation, route, run.output, round);
                    suspend(run);
                    summary.append(run.policy).append(',').append(result.row(round));
                    Files.writeString(output.resolve("summary.csv"), summary);
                    System.out.println(run.policy + " replay " + round + ": " + result);
                    if (!result.passed()) {
                        throw new AssertionError("Route freshness/restoration failed");
                    }
                }
            }
        } finally {
            for (var run : runs) {
                run.renderer.close();
            }
            System.clearProperty("engine.bvhMedianReference");
            System.clearProperty("engine.bvhPrimitiveLeafSize");
        }
    }

    private static Run warmup(Path output, CameraRoute route, boolean median) throws Exception {
        System.setProperty("engine.bvhMedianReference", Boolean.toString(median));
        System.setProperty("engine.bvhPrimitiveLeafSize", median ? "4" : "8");
        String policy = median ? "median" : "sah";
        Path directory = output.resolve(policy);
        Files.createDirectories(directory);
        var navigation = new GameNavigation();
        navigation.resize(960, 600);
        var renderer = new GameRenderer(navigation, BlockWorld.landscape());
        var run = new Run(policy, navigation, renderer, directory);
        try {
            renderer.adaptive(false);
            var result = GameRouteBenchmark.replay(renderer, navigation, route, directory, 0);
            suspend(run);
            System.out.println(policy + " warmup: " + result);
            return run;
        } catch (Exception | Error failure) {
            renderer.close();
            throw failure;
        }
    }

    private static void suspend(Run run) throws InterruptedException {
        run.navigation.active(false);
        while (!run.renderer.suspended()) {
            Thread.sleep(1);
        }
    }
}
