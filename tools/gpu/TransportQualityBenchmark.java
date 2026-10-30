package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/**
 * Matched frozen combat states; compare optional transport against unchanged 128-spp references.
 */
public final class TransportQualityBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path candidate = Path.of(args[1]);
        Path referenceShader = Path.of(args.length > 2 ? args[2] : "out/gpu/diffuse.spv");
        var frames = LightingQualityScene.frames();
        var images = new LinkedHashMap<String, List<float[]>>();
        for (String implementation : new String[] {"reference", "roulette"}) {
            try (var kernel =
                    new GpuKernel(
                            () -> {},
                            implementation.equals("reference") ? referenceShader : candidate)) {
                for (String mode : new String[] {"native", "half", "split"}) {
                    var settings =
                            RenderSettings.defaults()
                                    .withPathDepth(8)
                                    .withTemporal(true, 1)
                                    .withDenoiser(Denoiser.NRD_RELAX)
                                    .withLightingScale(mode.equals("split") ? .5 : 1)
                                    .withPathRoulette(
                                            args.length > 3 && implementation.equals("roulette"));
                    var raw = new GpuKernel.Snapshot();
                    var shown = new GpuKernel.Snapshot();
                    var values = new ArrayList<float[]>();
                    images.put(implementation + "-" + mode, values);
                    for (int state = -1; state < frames.size(); state++) {
                        var frame = frames.get(Math.max(0, state));
                        var input =
                                mode.equals("half")
                                        ? new RenderView(frame.view().camera(), 256, 144)
                                        : GpuLighting.sampled(frame.view(), settings);
                        var presentation = mode.equals("half") ? input : frame.view();
                        kernel.trace(
                                frame.world(),
                                input,
                                settings,
                                0,
                                new GpuKernel.Destination(null, null, raw, 1, shown, presentation));
                        if (state == -1) {
                            kernel.temporal.reset();
                            continue;
                        }
                        var image = shown.read(kernel.gpu).clone();
                        values.add(mode.equals("half") ? enlarge(image) : image);
                    }
                    raw.source.close();
                    shown.source.close();
                    kernel.temporal.reset();
                }
            }
        }
        double[] total = new double[images.size()];
        try (var kernel = new GpuKernel(() -> {}, referenceShader);
                var csv = Files.newBufferedWriter(output.resolve("quality.csv"))) {
            csv.write("state," + String.join(",", images.keySet()) + "\n");
            var settings = RenderSettings.defaults().withPathDepth(8);
            for (int state = 0; state < frames.size(); state++) {
                var frame = frames.get(state);
                var reference = new float[512 * 288 * 3];
                for (int sample = 0; sample < 128; sample++)
                    kernel.trace(frame.world(), frame.view(), settings, sample, reference);
                csv.write(Integer.toString(state));
                int index = 0;
                for (var entry : images.entrySet()) {
                    double error =
                            ReconstructionBenchmark.error(entry.getValue().get(state), reference);
                    total[index++] += error;
                    csv.write("," + error);
                    if (state == 3 || state == 7 || state == 11)
                        ReconstructionBenchmark.save(
                                output.resolve(entry.getKey() + "-" + state + ".png"),
                                entry.getValue().get(state),
                                512,
                                288);
                }
                csv.write("\n");
                if (state == 3 || state == 7 || state == 11)
                    ReconstructionBenchmark.save(
                            output.resolve("reference128-" + state + ".png"), reference, 512, 288);
            }
        }
        int index = 0;
        for (String mode : images.keySet())
            System.out.printf(
                    Locale.ROOT,
                    "%s mean display-space MSE %.8f%n",
                    mode,
                    total[index++] / frames.size());
    }

    private static float[] enlarge(float[] source) {
        var output = new float[512 * 288 * 3];
        for (int y = 0; y < 288; y++)
            for (int x = 0; x < 512; x++)
                for (int c = 0; c < 3; c++)
                    output[(y * 512 + x) * 3 + c] = source[((y / 2) * 256 + x / 2) * 3 + c];
        return output;
    }
}
