package gpu;

import engine.RenderSettings;
import engine.RenderView;
import engine.WorldSnapshot;

import game.GameCapture;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Fixed 1080p captured-scene costs; packed readback, no game simulation or window. */
public final class TraversalBenchmark {
    public static void main(String[] args) throws Exception {
        var capture = GameCapture.load(Path.of(args[0]));
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        var view = new RenderView(shown.view().camera(), 1920, 1080);
        var output = Path.of(args[1]);
        Files.createDirectories(output);
        var settings = shown.settings().withSamplesPerBatch(1).withInteractive(false, 8.333, 0, 0);
        int[] packed = new int[view.width() * view.height()];
        var snapshot = new GpuKernel.Snapshot();
        try (var kernel = new GpuKernel();
                var rows = Files.newBufferedWriter(output.resolve("samples.csv"))) {
            System.out.println("device=" + kernel.gpu.description);
            System.out.println(
                    "capture=" + args[0] + " camera=" + view.camera() + " settings=" + settings);
            System.out.println("round,sampling,medianGpuMs,p95GpuMs,medianHostMs,p95HostMs");
            rows.write("round,sampling,sample,gpuMs,hostMs\n");
            for (int round = 0; round < 3; round++) {
                for (boolean smooth :
                        round % 2 == 0
                                ? new boolean[] {false, true}
                                : new boolean[] {true, false}) {
                    var selected = settings.withFoveated(smooth).withSmoothFoveation(smooth);
                    double[] gpu = new double[100], host = new double[100];
                    for (int i = -30; i < 100; i++) {
                        kernel.trace(
                                world, view, selected, 0, null, packed, snapshot, shown.exposure());
                        if (i >= 0) {
                            gpu[i] = kernel.executeNanos / 1e6;
                            host[i] = kernel.hostNanos / 1e6;
                            rows.write(
                                    String.format(
                                            Locale.ROOT,
                                            "%d,%s,%d,%.6f,%.6f%n",
                                            round,
                                            smooth,
                                            i,
                                            gpu[i],
                                            host[i]));
                        }
                    }
                    Arrays.sort(gpu);
                    Arrays.sort(host);
                    System.out.printf(
                            Locale.ROOT,
                            "%d,%s,%.6f,%.6f,%.6f,%.6f%n",
                            round,
                            smooth,
                            gpu[49],
                            gpu[94],
                            host[49],
                            host[94]);
                }
            }
            // Smaller exact sample comparisons keep baseline evidence inexpensive to retain.
            var preview = new RenderView(view.camera(), 384, 216);
            for (boolean smooth : new boolean[] {false, true}) {
                savePixels(
                        kernel,
                        world,
                        preview,
                        settings.withFoveated(smooth).withSmoothFoveation(smooth),
                        output,
                        smooth);
            }
        }
    }

    private static void savePixels(
            GpuKernel kernel,
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            Path output,
            boolean smooth)
            throws Exception {
        var raw = new float[view.width() * view.height() * 3];
        for (int sample = 0; sample < 16; sample++) {
            kernel.trace(world, view, settings, sample, raw);
            if (sample == 0 || sample == 15) {
                try (var bytes =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(
                                                output.resolve(
                                                        "pixels-"
                                                                + smooth
                                                                + "-"
                                                                + (sample + 1)
                                                                + ".f32"))))) {
                    for (float value : raw) bytes.writeFloat(value);
                }
            }
        }
    }
}
