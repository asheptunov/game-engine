package game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/** Window-free game update and image-conversion benchmark, paced by elapsed wall time. */
public final class GameRouteBenchmark {
    private GameRouteBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "out/game-route");
        boolean adaptive = args.length < 2 || Boolean.parseBoolean(args[1]);
        Files.createDirectories(output);
        var route = CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        Files.copy(
                Path.of("benchmarks/game/camera-route.csv"),
                output.resolve("camera-route.csv"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var blocks = BlockWorld.landscape();
        var navigation = new GameNavigation();
        navigation.resize(960, 600);
        try (var renderer = new GameRenderer(navigation, blocks)) {
            renderer.adaptive(adaptive);
            Files.writeString(
                    output.resolve("metadata.txt"),
                    "CPU="
                            + System.getProperty("game.cpu", "unspecified")
                            + "\nJDK="
                            + System.getProperty("java.runtime.version")
                            + "\nlogicalProcessors="
                            + Runtime.getRuntime().availableProcessors()
                            + "\nblocks="
                            + blocks.blocks().size()
                            + "\nwindowReference=960x600 (no native window)\nrequested=320x200"
                            + "\nsettings="
                            + renderer.settings()
                            + "\nrouteSHA256="
                            + java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            Files.readAllBytes(
                                                                    output.resolve(
                                                                            "camera-route.csv"))))
                            + "\n"
                            + "metric=game copied-image publication; excludes AWT/monitor/input"
                            + " delivery\n");
            var summary =
                    new StringBuilder(
                            "run,freshMoving,p95Millis,maxMillis,restoredSeconds,firstRestoredSpp,finalSpp,passed\n");
            boolean passed = true;
            for (int run = 0; run <= 3; run++) {
                route.apply(navigation, 0);
                var result = replay(renderer, navigation, route, output, run);
                if (run > 0) {
                    summary.append(result.row(run));
                    passed &= result.passed();
                }
                System.out.println((run == 0 ? "Warmup" : "Replay " + run) + ": " + result);
                Files.writeString(output.resolve("summary.csv"), summary);
            }
            if (!passed) {
                throw new AssertionError(
                        "G4 freshness/restoration target missed; inspect " + output);
            }
        }
    }

    private record Result(
            int fresh,
            double p95,
            double maximum,
            double restored,
            long firstSamples,
            long finalSamples) {
        boolean passed() {
            return fresh > 0 && p95 <= 200 && restored >= 0 && finalSamples > firstSamples;
        }

        String row(int run) {
            return String.format(
                    Locale.ROOT,
                    "%d,%d,%.3f,%.3f,%.3f,%d,%d,%s%n",
                    run,
                    fresh,
                    p95,
                    maximum,
                    restored,
                    firstSamples,
                    finalSamples,
                    passed());
        }
    }

    private static Result replay(
            GameRenderer renderer,
            GameNavigation navigation,
            CameraRoute route,
            Path output,
            int run)
            throws Exception {
        var measurements = new Measurements();
        var finalNavigation = new GameNavigation();
        route.apply(finalNavigation, 25);
        var finalCamera = finalNavigation.view().camera();
        long minimumGeneration = renderer.restart();
        long start = System.nanoTime();
        renderer.observePublications(
                frame -> {
                    if (frame.generation() >= minimumGeneration) {
                        measurements.accept(
                                frame, (frame.publicationNanos() - start) / 1e9, finalCamera);
                    }
                });
        while (true) {
            double seconds = (System.nanoTime() - start) / 1e9;
            route.apply(navigation, seconds);
            if (renderer.error() != null) {
                throw new AssertionError(renderer.error());
            }
            if (seconds >= 30) {
                break;
            }
            Thread.sleep(2);
        }
        renderer.observePublications(null);
        Files.writeString(output.resolve("publications-" + run + ".csv"), measurements.rows);
        return measurements.result();
    }

    private static final class Measurements {
        private final ArrayList<Double> intervals = new ArrayList<>();
        private final StringBuilder rows =
                new StringBuilder("seconds,width,height,spp,generation,eyeX,eyeY,eyeZ\n");
        private double lastMoving;
        private double restored = -1;
        private long firstSamples;
        private long finalSamples;
        private int fresh;

        void accept(GameRenderer.Frame frame, double published, engine.Camera current) {
            if (published < 0 || published > 30) {
                return;
            }
            var eye = frame.camera().eye();
            rows.append(
                    String.format(
                            Locale.ROOT,
                            "%.6f,%d,%d,%d,%d,%.6f,%.6f,%.6f%n",
                            published,
                            frame.image().getWidth(),
                            frame.image().getHeight(),
                            frame.samples(),
                            frame.generation(),
                            eye.x(),
                            eye.y(),
                            eye.z()));
            if (published <= 25) {
                intervals.add((published - lastMoving) * 1000);
                lastMoving = published;
                fresh++;
            } else if (frame.image().getWidth() == 320
                    && frame.image().getHeight() == 200
                    && frame.camera().equals(current)) {
                recordRefinement(published, frame.samples());
            }
        }

        private void recordRefinement(double published, long samples) {
            if (restored < 0) {
                restored = published - 25;
                firstSamples = samples;
            }
            finalSamples = samples;
        }

        Result result() {
            intervals.add((25 - lastMoving) * 1000);
            intervals.sort(Double::compare);
            return new Result(
                    fresh,
                    intervals.get((int) Math.ceil(intervals.size() * .95) - 1),
                    intervals.getLast(),
                    restored,
                    firstSamples,
                    finalSamples);
        }
    }
}
