package game;

import engine.Atmosphere;
import engine.RenderEngine;
import engine.RenderImage;
import engine.RenderSession;
import engine.RenderSettings;
import engine.WorldSnapshot;

import math.Vec3;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/** Fixed camera comparisons and headless quality/performance evidence. */
public final class AtmosphereScenes {
    private AtmosphereScenes() {}

    public static void main(String[] args) throws Exception {
        String backend = args.length > 0 ? args[0] : "cpu";
        Path output = Path.of(args.length > 1 ? args[1] : "out/atmosphere");
        boolean full = args.length > 2 && args[2].equals("full");
        Files.createDirectories(output);
        var rows =
                new StringBuilder(
                        "backend,hour,hazy,width,height,spp,elapsedMs,traceMs,rmse,maxError\n");
        for (double hour : new double[] {12, 17.8, 18.2}) {
            fixture(
                    backend,
                    output,
                    rows,
                    hour,
                    false,
                    full ? 480 : 64,
                    full ? 300 : 64,
                    full ? 4 : 1);
        }
        fixture(backend, output, rows, 6.3, true, full ? 480 : 64, full ? 300 : 64, full ? 4 : 1);
        Files.writeString(output.resolve(backend + "-scenes.csv"), rows);
        Files.writeString(
                output.resolve(backend + "-runtime.txt"),
                System.getProperty("os.name")
                        + "\n"
                        + System.getProperty("java.runtime.version")
                        + "\ncpus="
                        + Runtime.getRuntime().availableProcessors()
                        + "\nmaxHeap="
                        + Runtime.getRuntime().maxMemory()
                        + "\n"
                        + RenderEngine.probe(backend));
    }

    static void conformance(String backend, Path output) throws Exception {
        AtmosphereLifecycleTest.sessionEdits(backend);
        var rows = new StringBuilder();
        fixture(backend, output, rows, 17.8, false, 64, 64, 1);
    }

    private static void fixture(
            String backend,
            Path output,
            StringBuilder rows,
            double hour,
            boolean hazy,
            int width,
            int height,
            int samples)
            throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(width, height);
        navigation.pose(new Vec3(0, 3, -6), 0, 0.1);
        var clock = new DayNightCycle();
        clock.hour(hour);
        var lighting =
                clock.atmosphericLighting(
                        hazy
                                ? Atmosphere.Parameters.hazyMorning()
                                : Atmosphere.Parameters.clearAir());
        var source = AdventureWorld.create();
        var world =
                new WorldSnapshot(
                        0, source.instances(), List.of(), List.of(lighting.sun()), lighting.sky());
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSampleTarget(samples)
                        .withSamplesPerBatch(1);
        String name = "air-" + hour + "-" + width + "x" + height + (hazy ? "-hazy" : "");
        long start = System.nanoTime();
        try (var session = RenderEngine.openSession(world, navigation.view(), settings, backend)) {
            for (int sample : samples == 1 ? new int[] {1} : new int[] {1, samples}) {
                try (var image = await(session, sample)) {
                    float[] values = BackendConformance.copy(image);
                    var raw = output.resolve(name + "-" + sample + ".rgb");
                    double[] errors = compareOrSave(backend, raw, values);
                    ImageIO.write(
                            GameRenderer.copyImage(image),
                            "png",
                            output.resolve(name + "-" + backend + "-" + sample + ".png").toFile());
                    String row =
                            String.format(
                                    Locale.ROOT,
                                    "%s,%.2f,%s,%d,%d,%d,%.3f,%.3f,%.8f,%.8f%n",
                                    backend,
                                    hour,
                                    hazy,
                                    width,
                                    height,
                                    sample,
                                    (System.nanoTime() - start) / 1e6,
                                    image.traceNanos() / 1e6,
                                    errors[0],
                                    errors[1]);
                    rows.append(row);
                    System.out.print(row);
                }
            }
        }
    }

    private static double[] compareOrSave(String backend, Path raw, float[] values)
            throws Exception {
        if (backend.equals("cpu")) {
            try (var file = new DataOutputStream(Files.newOutputStream(raw))) {
                for (float value : values) {
                    file.writeFloat(value);
                }
            }
            return new double[2];
        }
        double square = 0;
        double maximum = 0;
        try (var file = new DataInputStream(Files.newInputStream(raw))) {
            for (float value : values) {
                BackendConformance.require(
                        Float.isFinite(value) && value >= 0, "finite nonnegative atmosphere image");
                double error = Math.abs(value - file.readFloat());
                square += error * error;
                maximum = Math.max(maximum, error);
            }
        }
        double rmse = Math.sqrt(square / values.length);
        BackendConformance.require(
                rmse <= .004 && maximum <= .05,
                "atmosphere backend agreement: rmse=" + rmse + ", max=" + maximum);
        return new double[] {rmse, maximum};
    }

    static RenderImage await(RenderSession session, long samples) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (image.generation() == session.progress(null).requestedGeneration()
                        && image.samples() >= samples) {
                    return image;
                }
                image.close();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("atmosphere publication timeout: " + session.status());
    }
}
