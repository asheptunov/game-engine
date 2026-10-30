package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/** Fixed frozen combat replay; isolates submission/host work without simulation or presentation. */
public final class FramePipelineBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var frames = LightingQualityScene.frames();
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(8)
                        .withPathRoulette(true)
                        .withTemporal(true, 1)
                        .withDenoiser(Denoiser.NRD_RELAX)
                        .withBloom(Bloom.OFF)
                        .withSamplesPerBatch(1)
                        .withSampleTarget(0)
                        .withFoveated(false)
                        .withFogDownsample(1);
        var summary =
                new StringBuilder(
                        "round,separate,split,resident,hostMedianMs,hostP95Ms,prepareMedianMs,gpuMedianMs,readbackMedianMs,submissionMean\n");
        for (int round = 0; round < 3; round++) {
            for (boolean separate : new boolean[] {round % 2 == 0, round % 2 != 0}) {
                System.setProperty("game.separateGpuSceneSubmission", Boolean.toString(separate));
                for (boolean split : new boolean[] {false, true}) {
                    for (boolean resident : new boolean[] {false, true}) {
                        var rows =
                                new StringBuilder(
                                        "frame,hostMs,prepareMs,gpuMs,readbackMs,submissions,allSubmitWaitMs\n");
                        var times = new ArrayList<double[]>();
                        var mode = settings.withLightingScale(split ? .5 : 1);
                        try (var kernel = new GpuKernel()) {
                            var raw = new GpuKernel.Snapshot();
                            var presentation = new GpuKernel.Snapshot();
                            var packed =
                                    resident ? null : new int[(split ? 1920 * 1080 : 960 * 540)];
                            for (int i = -36; i < 180; i++) {
                                var frame = frames.get(Math.floorMod(i, frames.size()));
                                var view = new RenderView(frame.view().camera(), 960, 540);
                                var outputView =
                                        new RenderView(
                                                frame.view().camera(),
                                                split ? 1920 : 960,
                                                split ? 1080 : 540);
                                long submits = kernel.gpu.completedSubmissions;
                                long waits = kernel.gpu.submissionWaitNanos;
                                kernel.trace(
                                        frame.world(),
                                        view,
                                        mode,
                                        0,
                                        new GpuKernel.Destination(
                                                null, packed, raw, 1, presentation, outputView));
                                if (i < 0) continue;
                                double[] sample = {
                                    kernel.hostNanos / 1e6,
                                    kernel.prepareNanos / 1e6,
                                    kernel.executeNanos / 1e6,
                                    kernel.readbackNanos / 1e6,
                                    kernel.gpu.completedSubmissions - submits,
                                    (kernel.gpu.submissionWaitNanos - waits) / 1e6
                                };
                                times.add(sample);
                                rows.append(
                                        String.format(
                                                Locale.ROOT,
                                                "%d,%.6f,%.6f,%.6f,%.6f,%.0f,%.6f%n",
                                                i,
                                                sample[0],
                                                sample[1],
                                                sample[2],
                                                sample[3],
                                                sample[4],
                                                sample[5]));
                            }
                        }
                        var line =
                                String.format(
                                        Locale.ROOT,
                                        "%d,%s,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                                        round,
                                        separate,
                                        split,
                                        resident,
                                        q(times, 0, .5),
                                        q(times, 0, .95),
                                        q(times, 1, .5),
                                        q(times, 2, .5),
                                        q(times, 3, .5),
                                        times.stream()
                                                .mapToDouble(t -> t[4])
                                                .average()
                                                .orElseThrow());
                        summary.append(line);
                        System.out.print(line);
                        Files.writeString(
                                output.resolve(
                                        round + "-" + separate + "-" + split + "-" + resident
                                                + ".csv"),
                                rows);
                    }
                }
            }
        }
        Files.writeString(output.resolve("summary.csv"), summary);
        System.clearProperty("game.separateGpuSceneSubmission");
    }

    private static double q(List<double[]> rows, int column, double fraction) {
        double[] sorted = rows.stream().mapToDouble(row -> row[column]).sorted().toArray();
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
}
