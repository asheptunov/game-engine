package game;

import engine.DisplayMapping;
import engine.RenderCapabilities;
import engine.RenderEngine;
import engine.RenderImage;
import engine.RenderSettings;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/** Frozen fixtures: CPU writes reference radiance, GPU compares without sharing tracing code. */
final class ParityImages {
    private ParityImages() {}

    static void run(String backend, Path output) throws Exception {
        detectorSelfTest();
        var rows =
                new StringBuilder(
                        "fixture,backend,width,height,depth,spp,seed,rmse,maxError,rmseLimit,maxLimit\n");
        for (String scene : List.of("gallery", "landscape", "world")) {
            for (int depth : new int[] {0, 2}) {
                fixture(backend, output, rows, scene, depth, false);
            }
        }
        fixture(backend, output, rows, "world", 2, true);
        Files.writeString(output.resolve(backend + "-images.csv"), rows);
    }

    private static void fixture(
            String backend, Path output, StringBuilder rows, String scene, int depth, boolean lens)
            throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(128, 80);
        var clock = new DayNightCycle();
        clock.hour(lens ? 0 : 9);
        var lighting = clock.lighting();
        if (lens) {
            navigation.optics(.05f, 8);
        }
        var source =
                switch (scene) {
                    case "gallery" -> BlockWorld.gallery().snapshot();
                    case "landscape" -> BlockWorld.landscape().snapshot();
                    default -> AdventureWorld.create();
                };
        var world = BackendConformance.world(source, lighting);
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(depth)
                        .withSampleTarget(8)
                        .withSamplesPerBatch(4);
        String name = scene + "-depth" + depth + (lens ? "-night-lens" : "-day");
        var raw = output.resolve(name + ".rgb");
        double[] error = {0, 0};
        try (var session = RenderEngine.openSession(world, navigation.view(), settings, backend);
                var image = BackendConformance.await(session, 8)) {
            float[] values = BackendConformance.copy(image);
            double rmseLimit = depth == 0 ? .002 : .04;
            double maxLimit = depth == 0 ? .02 : .25;
            if (backend.equals("cpu")) {
                write(raw, values);
            } else {
                error = compare(read(raw, values.length), values, rmseLimit, maxLimit, name);
            }
            checkDisplay(image);
            ImageIO.write(
                    GameRenderer.copyImage(image),
                    "png",
                    output.resolve(name + "-" + backend + ".png").toFile());
            String metadata =
                    "profile="
                            + RenderCapabilities.COMMON_GAME_PROFILE
                            + "\nworld="
                            + scene
                            + "\nlighting="
                            + lighting
                            + "\nview="
                            + navigation.view()
                            + "\nsettings="
                            + settings
                            + "\n";
            var metadataPath = output.resolve(name + "-settings.txt");
            if (backend.equals("cpu")) {
                Files.writeString(metadataPath, metadata);
            } else {
                BackendConformance.require(
                        Files.readString(metadataPath).equals(metadata), "matching fixture inputs");
            }
            rows.append(
                    String.format(
                            Locale.ROOT,
                            "%s,%s,128,80,%d,8,1,%.9f,%.9f,%.3f,%.3f%n",
                            name,
                            backend,
                            depth,
                            error[0],
                            error[1],
                            rmseLimit,
                            maxLimit));
        }
    }

    private static void checkDisplay(RenderImage image) {
        for (float exposure : new float[] {.5f, 1, 2}) {
            var display = GameRenderer.copyImage(image, exposure);
            for (int y : new int[] {0, image.height() - 1}) {
                for (int x : new int[] {0, image.width() / 2, image.width() - 1}) {
                    int rgb = display.getRGB(x, image.height() - 1 - y) & 0xffffff;
                    int expected = 0;
                    for (int channel = 0; channel < 3; channel++) {
                        expected =
                                (expected << 8)
                                        | Byte.toUnsignedInt(
                                                DisplayMapping.encode(
                                                        image.rawPixels().value(channel, x, y),
                                                        exposure));
                    }
                    BackendConformance.require(
                            rgb == expected, "display orientation/exposure/channel mapping");
                }
            }
        }
    }

    private static double[] compare(
            float[] expected, float[] actual, double rmseLimit, double maxLimit, String name) {
        double squared = 0;
        double maximum = 0;
        for (int index = 0; index < expected.length; index++) {
            BackendConformance.require(
                    Float.isFinite(actual[index]) && actual[index] >= 0,
                    name + " invalid radiance at " + index);
            double error = Math.abs(expected[index] - actual[index]);
            squared += error * error;
            maximum = Math.max(maximum, error);
        }
        double rmse = Math.sqrt(squared / expected.length);
        BackendConformance.require(
                rmse <= rmseLimit && maximum <= maxLimit,
                name
                        + " mismatch: RMSE="
                        + rmse
                        + ", max="
                        + maximum
                        + "; limits="
                        + rmseLimit
                        + ","
                        + maxLimit);
        return new double[] {rmse, maximum};
    }

    private static void detectorSelfTest() {
        float[] reference = new float[30000];
        float[] changed = new float[30000];
        changed[17000] = 1;
        boolean rejected = false;
        try {
            compare(reference, changed, .04, .25, "deliberately missing small feature");
        } catch (AssertionError expected) {
            rejected = true;
        }
        BackendConformance.require(
                rejected,
                "comparison must detect a sparse fixture corruption even below RMSE limit");
    }

    private static void write(Path path, float[] values) throws Exception {
        try (var stream = new DataOutputStream(Files.newOutputStream(path))) {
            stream.writeInt(values.length);
            for (float value : values) {
                stream.writeFloat(value);
            }
        }
    }

    private static float[] read(Path path, int length) throws Exception {
        try (var stream = new DataInputStream(Files.newInputStream(path))) {
            BackendConformance.require(stream.readInt() == length, "reference dimensions");
            var values = new float[length];
            for (int index = 0; index < length; index++) {
                values[index] = stream.readFloat();
            }
            return values;
        }
    }
}
