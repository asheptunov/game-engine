package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/**
 * Paired fixed-state 1080p jobs, including packed display readback; excludes simulation and paint.
 */
public final class FsrBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var frames = LightingQualityScene.frames();
        var summary =
                new StringBuilder(
                        "round,mode,hostMedianMs,hostP95Ms,gpuMedianMs,upscaleMedianMs,denoiseMedianMs,visibilityMedianMs,peakDeviceBytes\n");
        String[] modes = {"half", "split", "fsr-performance", "fsr-quality"};
        for (int round = 0; round < 3; round++)
            for (int m = 0; m < modes.length; m++) {
                String mode = modes[round % 2 == 0 ? m : modes.length - 1 - m];
                var settings =
                        RenderSettings.defaults()
                                .withPathDepth(8)
                                .withPathRoulette(true)
                                .withTemporal(true, 1)
                                .withDenoiser(Denoiser.NRD_RELAX)
                                .withLightingScale(mode.equals("split") ? .5 : 1)
                                .withUpscaler(
                                        mode.equals("fsr-performance")
                                                ? Upscaler.FSR_PERFORMANCE
                                                : mode.equals("fsr-quality")
                                                        ? Upscaler.FSR_QUALITY
                                                        : Upscaler.OFF);
                var rows =
                        new StringBuilder("frame,hostMs,gpuMs,upscaleMs,denoiseMs,visibilityMs\n");
                var times = new ArrayList<double[]>();
                try (var kernel = new GpuKernel()) {
                    var raw = new GpuKernel.Snapshot();
                    var presented = new GpuKernel.Snapshot();
                    int w = mode.equals("half") ? 960 : 1920, h = mode.equals("half") ? 540 : 1080;
                    var packed = new int[w * h];
                    for (int i = -36; i < 180; i++) {
                        var frame = frames.get(Math.floorMod(i, frames.size()));
                        var view = new RenderView(frame.view().camera(), w, h);
                        kernel.trace(
                                frame.world(),
                                GpuLighting.sampled(view, settings),
                                settings,
                                0,
                                new GpuKernel.Destination(null, packed, raw, 1, presented, view));
                        if (i < 0) continue;
                        double[] values = {
                            kernel.hostNanos / 1e6,
                            kernel.executeNanos / 1e6,
                            kernel.upscalingNanos / 1e6,
                            kernel.reconstructionNanos / 1e6,
                            kernel.visibilityNanos / 1e6
                        };
                        times.add(values);
                        rows.append(i);
                        for (double value : values)
                            rows.append(String.format(Locale.ROOT, ",%.6f", value));
                        rows.append('\n');
                    }
                    String line =
                            String.format(
                                    Locale.ROOT,
                                    "%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",
                                    round,
                                    mode,
                                    q(times, 0, .5),
                                    q(times, 0, .95),
                                    q(times, 1, .5),
                                    q(times, 2, .5),
                                    q(times, 3, .5),
                                    q(times, 4, .5),
                                    kernel.gpu.peakBytes);
                    summary.append(line);
                    System.out.print(line);
                }
                Files.writeString(output.resolve(round + "-" + mode + ".csv"), rows);
            }
        Files.writeString(output.resolve("summary.csv"), summary);
    }

    private static double q(List<double[]> rows, int column, double fraction) {
        double[] values = rows.stream().mapToDouble(v -> v[column]).sorted().toArray();
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * fraction) - 1)];
    }
}
