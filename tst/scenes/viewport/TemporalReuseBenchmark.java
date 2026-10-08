package engine;

import math.Vec3;

import scenes.viewport.*;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * Window-free motion/quality benchmark. Soft equal-time budget includes guides and reconstruction.
 */
public class TemporalReuseBenchmark {
    private static final int W = 160, H = 100, FRAMES = 18, REFERENCE = 128;

    private static ViewportState scene(String preset, boolean temporal) {
        var s = TemporalReconstructionTest.plane();
        ScenePresets.load(s, preset.equals("diffuse-room") ? "rough-room" : preset);
        if (preset.equals("diffuse-room")) {
            for (int i = 0; i < s.instances().size(); i++) {
                var o = s.instances().get(i);
                s.instances().set(i, o.withMaterial(o.material().withKind(Material.Kind.DIFFUSE)));
            }
        }
        s.resolution(W, H);
        s.presentationAspect((float) W / H);
        s.workers(Math.min(14, Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
        s.temporal(temporal);
        s.samplesPerFrame(1);
        return s;
    }

    private static void camera(ViewportState s, int frame) {
        // Translation + a direction reversal + cut; the cuts deliberately invalidate history.
        float x = frame < 10 ? frame * .03f : (18.5f - frame) * .03f;
        if (frame >= 15) x += 1;
        s.eye(new Vec3(x, 0, -1));
        s.cameraSensor(
                new engine.objects.Rect(
                        new Vec3(-.8f + x, -.5f, 0), new Vec3(1.6f, 0, 0), new Vec3(0, 1, 0)));
    }

    private record Run(
            float[][][][] images,
            double[] millis,
            double[] spp,
            double reconstruction,
            double confidence,
            long memory,
            long guides,
            int reused,
            double rejectedDelta) {}

    private static Run run(String preset, boolean temporal, double budget) {
        var s = scene(preset, temporal);
        var t = new TemporalReconstruction();
        var images = new float[FRAMES][][][];
        var ms = new double[FRAMES];
        var spp = new double[FRAMES];
        double lastTrace = 0,
                lastReconstruction = 0,
                totalReconstruction = 0,
                confidence = 0,
                rejectedDelta = 0;
        long memory = 0, guides = 0;
        int reused = 0;
        try (var tracer = new DirectRgbTracer(s)) {
            for (int frame = -12; frame < FRAMES; frame++) {
                camera(s, frame);
                long start = System.nanoTime();
                do {
                    long before = System.nanoTime();
                    tracer.trace();
                    lastTrace = (System.nanoTime() - before) / 1e6;
                    guides += tracer.guideRays;
                } while (budget > 0
                        && (System.nanoTime() - start) / 1e6 + lastTrace * 1.15 + lastReconstruction
                                < budget
                        && s.accumulatedSamples() < 64);
                var raw = tracer.radianceBuffer();
                float[][][] result = raw;
                if (temporal) {
                    result =
                            t.reconstruct(
                                    raw,
                                    tracer.surfaceGuide(),
                                    s.renderKey(),
                                    s.accumulatedSamples(),
                                    System.nanoTime(),
                                    s.workers());
                    lastReconstruction = t.stats().nanos() / 1e6;
                }
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (frame >= 0) {
                    var g = tracer.surfaceGuide();
                    if (temporal && g != null)
                        for (int y = 0; y < H; y++)
                            for (int x = 0; x < W; x++)
                                if (g.surface[y * W + x] == 0)
                                    for (int c = 0; c < 3; c++)
                                        rejectedDelta =
                                                Math.max(
                                                        rejectedDelta,
                                                        Math.abs(result[c][y][x] - raw[c][y][x]));
                    images[frame] = TemporalReconstructionTest.copy(result);
                    ms[frame] = elapsed;
                    spp[frame] = s.accumulatedSamples();
                    totalReconstruction += lastReconstruction;
                    confidence += t.stats().confidence();
                    memory = Math.max(memory, t.stats().bytes());
                    reused += t.stats().reused();
                }
            }
        }
        return new Run(
                images,
                ms,
                spp,
                totalReconstruction / FRAMES,
                confidence / FRAMES,
                memory,
                guides,
                reused,
                rejectedDelta);
    }

    private static float[][][][] reference(String preset) {
        var s = scene(preset, false);
        var images = new float[FRAMES][][][];
        try (var tracer = new DirectRgbTracer(s)) {
            for (int frame = 0; frame < FRAMES; frame++) {
                camera(s, frame);
                for (int sample = 0; sample < REFERENCE; sample++) tracer.trace();
                images[frame] = TemporalReconstructionTest.copy(tracer.radianceBuffer());
            }
        }
        return images;
    }

    private static double mse(Run run, float[][][][] reference) {
        double sum = 0;
        for (int f = 0; f < FRAMES; f++)
            for (int c = 0; c < 3; c++)
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) {
                        double delta = run.images[f][c][y][x] - reference[f][c][y][x];
                        sum += delta * delta;
                    }
        return sum / (FRAMES * W * H * 3);
    }

    private static void report(String preset, String mode, Run r, float[][][][] ref) {
        var sorted = r.millis.clone();
        Arrays.sort(sorted);
        double cutError = 0, reverseError = 0;
        for (int f = 10; f <= 15; f++)
            for (int c = 0; c < 3; c++)
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) {
                        double delta = r.images[f][c][y][x] - ref[f][c][y][x];
                        if (f == 15) cutError += delta * delta;
                        else reverseError += delta * delta;
                    }
        System.out.printf(
                Locale.ROOT,
                "%s,%s,%.3f,%.3f,%.2f,%.3f,%.6g,%.1f,%.2f,%d,%d,%.6g,%.6g,%.6g%n",
                preset,
                mode,
                Arrays.stream(r.millis).average().orElse(0),
                sorted[(int) Math.ceil(FRAMES * .95) - 1],
                Arrays.stream(r.spp).average().orElse(0),
                r.reconstruction,
                mse(r, ref),
                r.confidence * 100,
                r.memory / 1048576.,
                r.guides,
                r.reused,
                r.rejectedDelta,
                reverseError / (5 * W * H * 3),
                cutError / (W * H * 3));
    }

    private static void preview(String preset, Run raw, Run temporal, float[][][][] ref)
            throws Exception {
        int scale = 3;
        var image = new BufferedImage(W * scale * 3, H * scale * 2, BufferedImage.TYPE_INT_RGB);
        int[] frames = {9, 15};
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 3; col++) {
                var rgb =
                        col == 0
                                ? raw.images[frames[row]]
                                : col == 1 ? temporal.images[frames[row]] : ref[frames[row]];
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) {
                        int color = 0;
                        for (int c = 0; c < 3; c++)
                            color = (color << 8) | (DisplayMapping.encode(rgb[c][y][x], 1) & 255);
                        for (int yy = 0; yy < scale; yy++)
                            for (int xx = 0; xx < scale; xx++)
                                image.setRGB(
                                        (col * W + x) * scale + xx,
                                        (row * H + y) * scale + yy,
                                        color);
                    }
            }
        ImageIO.write(image, "png", Path.of("out/cli/p5-" + preset + "-comparison.png").toFile());
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("out/cli"));
        if (Arrays.asList(args).contains("cost")) {
            costs();
            return;
        }
        System.out.println(
                "P5 160x100, depth preset (8/3/12), seed=1, tile=32, workers up to14, 12 warmup/18"
                        + " motion frames, reference128spp");
        System.out.println(
                "preset,mode,mean_ms,p95_ms,raw_spp,reconstruct_ms,linear_mse,mean_blend_percent,history_MiB,guide_rays_with_warmup,reused_pixels,max_rejected_delta,reverse_mse,cut_mse");
        var presets =
                args.length == 0
                        ? List.of("diffuse-room", "bounce-room", "glass", "volume-room")
                        : List.of(args);
        for (String preset : presets) {
            var ref = reference(preset);
            var raw = run(preset, false, 0);
            var temporal = run(preset, true, 0);
            report(preset, "raw1", raw, ref);
            report(preset, "temporal1", temporal, ref);
            preview(preset, raw, temporal, ref);
            // Both modes get the same soft 16.67ms tracing+reconstruction budget, allowing
            // completed-pass overshoot.
            report(preset, "raw16.67ms", run(preset, false, 16.6667), ref);
            report(preset, "temporal16.67ms", run(preset, true, 16.6667), ref);
        }
    }

    private static void costs() {
        System.out.println(
                "Cost only, 18 warmup/18 measured motion, 1 raw spp, seed1, depth3/8, workers up"
                        + " to14, tile32");
        System.out.println(
                "preset,grid,mode,mean_ms,p95_ms,reconstruct_ms,history_MiB,extra_max_payload_MiB");
        for (String preset : List.of("bounce-room", "glass"))
            for (int width : new int[] {360, 1440})
                for (boolean enabled : new boolean[] {false, true}) {
                    var s = scene(preset, enabled);
                    s.resolution(width, width * 5 / 8);
                    var t = new TemporalReconstruction();
                    double[] ms = new double[18];
                    double reconstruction = 0;
                    long memory = 0;
                    try (var tracer = new DirectRgbTracer(s)) {
                        for (int frame = -18; frame < 18; frame++) {
                            camera(s, frame);
                            long start = System.nanoTime();
                            var raw = tracer.trace();
                            if (enabled)
                                t.reconstruct(
                                        raw,
                                        tracer.surfaceGuide(),
                                        s.renderKey(),
                                        s.accumulatedSamples(),
                                        System.nanoTime(),
                                        s.workers());
                            double elapsed = (System.nanoTime() - start) / 1e6;
                            if (frame >= 0) {
                                ms[frame] = elapsed;
                                reconstruction += t.stats().nanos() / 1e6;
                                memory = Math.max(memory, t.stats().bytes());
                            }
                        }
                    }
                    var sorted = ms.clone();
                    Arrays.sort(sorted);
                    System.out.printf(
                            Locale.ROOT,
                            "%s,%dx%d,%s,%.3f,%.3f,%.3f,%.2f,%.2f%n",
                            preset,
                            width,
                            width * 5 / 8,
                            enabled ? "temporal" : "raw",
                            Arrays.stream(ms).average().orElse(0),
                            sorted[17],
                            reconstruction / 18,
                            memory / 1048576.,
                            enabled ? 128L * width * (width * 5 / 8) / 1048576. : 0);
                }
    }
}
