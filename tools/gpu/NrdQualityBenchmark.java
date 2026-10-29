package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/** Equal frozen combat states at 512x288; 128 spp references, no wall-time equivalence claim. */
public final class NrdQualityBenchmark {
    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]);
        Files.createDirectories(directory);
        var frames = LightingQualityScene.frames();
        var settings = RenderSettings.defaults().withPathDepth(8).withTemporal(true, 1);
        var modes = new String[] {"raw", "temporal", "half", "split", "nrd", "nrdhalf", "nrdsplit"};
        var images = new LinkedHashMap<String, List<float[]>>();
        try (var kernel = new GpuKernel()) {
            for (String mode : modes) {
                var values = new ArrayList<float[]>();
                images.put(mode, values);
                kernel.temporal.reset();
                // Compile and allocate this mode before the fixed-state sequence, so cold setup
                // cannot expire a history interval in the first measured transition.
                var warmFrame = frames.getFirst();
                var warmSettings =
                        settings.withDenoiser(
                                        mode.startsWith("nrd")
                                                ? Denoiser.NRD_RELAX
                                                : Denoiser.BUILT_IN)
                                .withTemporal(!mode.equals("raw"), 1)
                                .withLightingScale(mode.endsWith("split") ? .5 : 1);
                var warmView =
                        mode.endsWith("half")
                                ? new RenderView(warmFrame.view().camera(), 256, 144)
                                : GpuLighting.sampled(warmFrame.view(), warmSettings);
                var warmOutput = new GpuKernel.Snapshot();
                kernel.trace(
                        warmFrame.world(),
                        warmView,
                        warmSettings,
                        0,
                        new GpuKernel.Destination(
                                null,
                                null,
                                new GpuKernel.Snapshot(),
                                1,
                                warmOutput,
                                mode.endsWith("half") ? warmView : warmFrame.view()));
                warmOutput.source.close();
                kernel.temporal.reset();
                for (var frame : frames) {
                    var output = frame.view();
                    var active =
                            settings.withDenoiser(
                                            mode.startsWith("nrd")
                                                    ? Denoiser.NRD_RELAX
                                                    : Denoiser.BUILT_IN)
                                    .withTemporal(!mode.equals("raw"), 1)
                                    .withLightingScale(mode.endsWith("split") ? .5 : 1);
                    var input =
                            mode.endsWith("half")
                                    ? new RenderView(output.camera(), 256, 144)
                                    : GpuLighting.sampled(output, active);
                    var presentation = mode.endsWith("half") ? input : output;
                    var raw = new GpuKernel.Snapshot();
                    var shown = new GpuKernel.Snapshot();
                    kernel.trace(
                            frame.world(),
                            input,
                            active,
                            0,
                            new GpuKernel.Destination(null, null, raw, 1, shown, presentation));
                    var image = shown.read(kernel.gpu).clone();
                    values.add(mode.endsWith("half") ? enlarge(image, 256, 144) : image);
                }
            }
            double[] errors = new double[modes.length];
            try (var csv = Files.newBufferedWriter(directory.resolve("quality.csv"))) {
                csv.write("frame," + String.join(",", modes) + "\n");
                for (int frame = 0; frame < frames.size(); frame++) {
                    var state = frames.get(frame);
                    var reference = new float[512 * 288 * 3];
                    for (int sample = 0; sample < 128; sample++)
                        kernel.trace(
                                state.world(),
                                state.view(),
                                settings.withTemporal(false, 2),
                                sample,
                                reference);
                    csv.write(Integer.toString(frame));
                    for (int i = 0; i < modes.length; i++) {
                        double error =
                                ReconstructionBenchmark.error(
                                        images.get(modes[i]).get(frame), reference);
                        errors[i] += error;
                        csv.write("," + error);
                    }
                    csv.write("\n");
                    if (frame == 3 || frame == 7 || frame == 11) {
                        ReconstructionBenchmark.save(
                                directory.resolve("reference-" + frame + ".png"),
                                reference,
                                512,
                                288);
                        for (String mode : modes)
                            ReconstructionBenchmark.save(
                                    directory.resolve(mode + "-" + frame + ".png"),
                                    images.get(mode).get(frame),
                                    512,
                                    288);
                    }
                }
            }
            for (int i = 0; i < modes.length; i++)
                System.out.printf(
                        Locale.ROOT,
                        "%s mean display-space MSE %.8f%n",
                        modes[i],
                        errors[i] / frames.size());
        }
    }

    private static float[] enlarge(float[] source, int width, int height) {
        var result = new float[width * height * 12];
        for (int y = 0; y < height * 2; y++)
            for (int x = 0; x < width * 2; x++)
                for (int c = 0; c < 3; c++)
                    result[(y * width * 2 + x) * 3 + c] = source[((y / 2) * width + x / 2) * 3 + c];
        return result;
    }
}
