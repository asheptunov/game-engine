package engine;

import engine.objects.Rect;

import math.Vec3;

import scenes.viewport.*;

import java.util.*;

/** Fixed captured-view quality/cost comparison; live scheduling is measured separately. */
public class TemporalBudgetBenchmark {
    static final int W = 160, H = 100, FRAMES = 18, REFERENCE = 128;
    static long seed = 1;

    static ViewportState scene(String preset) {
        var s = TemporalReconstructionTest.plane();
        ScenePresets.load(s, preset);
        s.resolution(W, H);
        s.presentationAspect((float) W / H);
        s.seed(seed);
        s.workers(Math.min(14, Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
        return s;
    }

    static void camera(ViewportState s, int frame) {
        float x = frame < 10 ? frame * .03f : (18.5f - frame) * .03f;
        if (frame >= 15) x += 1;
        s.eye(new Vec3(x, 0, -1));
        s.cameraSensor(
                new Rect(new Vec3(-.8f + x, -.5f, 0), new Vec3(1.6f, 0, 0), new Vec3(0, 1, 0)));
    }

    static float[][][][] references(String preset) {
        var s = scene(preset);
        s.seed(seed + 1000);
        s.samplesPerFrame(8);
        s.sampleTarget(REFERENCE);
        var images = new float[FRAMES][][][];
        try (var tracer = new DirectRgbTracer(s)) {
            for (int frame = 0; frame < FRAMES; frame++) {
                camera(s, frame);
                do {
                    tracer.trace();
                } while (s.accumulatedSamples() < REFERENCE);
                images[frame] = TemporalReconstructionTest.copy(tracer.radianceBuffer());
            }
        }
        return images;
    }

    static float[][][][] run(
            String preset,
            String mode,
            boolean temporal,
            boolean budget,
            int requested,
            double scale,
            float[][][][] refs) {
        var live = scene(preset);
        live.temporal(temporal);
        live.temporalBudget(budget);
        live.samplesPerFrame(requested);
        live.motionScale(scale);
        var policy = new InteractiveResolution();
        var reconstruction = new TemporalReconstruction();
        long clock = 1_000_000_000L;
        camera(live, -101);
        policy.observe(live, clock);
        var images = new float[FRAMES][][][];
        var times = new double[FRAMES];
        double error = 0, cut = 0, reverse = 0, edge = 0;
        long guides = 0, primary = 0;
        int width = 0, height = 0, cap = 0;
        try (var tracer = new DirectRgbTracer(live)) {
            for (int frame = -100; frame < FRAMES; frame++) {
                clock += 40_000_000L;
                camera(live, frame);
                policy.observe(live, clock);
                policy.choose(live, clock);
                var snapshot = live.renderSnapshot();
                cap = live.movingBatch(true);
                snapshot.samplesPerFrame(cap);
                snapshot.sampleTarget(cap);
                if (budget
                        && !reconstruction.compatibleHistory(
                                snapshot.renderKey(), System.nanoTime())) {
                    cap = requested;
                    snapshot.samplesPerFrame(cap);
                    snapshot.sampleTarget(cap);
                }
                // Match P4's one-spp lower-grid raw work without feedback-driven grid changes.
                if (mode.equals("raw-low1"))
                    snapshot.resolution((int) Math.round(W * scale), (int) Math.round(H * scale));
                long start = System.nanoTime();
                tracer.trace(snapshot, () -> false);
                var raw = tracer.radianceBuffer();
                var rgb =
                        temporal
                                ? reconstruction.reconstruct(
                                        raw,
                                        tracer.surfaceGuide(),
                                        snapshot.renderKey(),
                                        snapshot.accumulatedSamples(),
                                        System.nanoTime(),
                                        snapshot.workers())
                                : raw;
                if (frame < 0) continue;
                times[frame] = (System.nanoTime() - start) / 1e6;
                guides += tracer.guideRays;
                primary += tracer.primaryRays;
                width = rgb[0][0].length;
                height = rgb[0].length;
                var displayed = new float[3][][];
                for (int c = 0; c < 3; c++) displayed[c] = Resampler.resample(rgb[c], H, W).buf();
                images[frame] = displayed;
                for (int c = 0; c < 3; c++)
                    for (int y = 0; y < H; y++)
                        for (int x = 0; x < W; x++) {
                            // Reference box filtering matches the linear display resampler.
                            double value = displayed[c][y][x];
                            double d = value - refs[frame][c][y][x];
                            error += d * d;
                            if (frame == 15) cut += d * d;
                            if (frame >= 10 && frame < 15) reverse += d * d;
                            if (x > 0) {
                                double gradient =
                                        (value - displayed[c][y][x - 1])
                                                - (refs[frame][c][y][x] - refs[frame][c][y][x - 1]);
                                edge += gradient * gradient;
                            }
                        }
            }
        }
        var sorted = times.clone();
        Arrays.sort(sorted);
        System.out.printf(
                Locale.ROOT,
                "%s,%s,%dx%d,%d,%d,%.3f,%.3f,%.6g,%d,%d,%.6g,%.6g,%.6g%n",
                preset,
                mode,
                width,
                height,
                requested,
                cap,
                Arrays.stream(times).average().orElse(0),
                sorted[17],
                error / (FRAMES * W * H * 3),
                primary,
                guides,
                reverse / (5 * W * H * 3),
                cut / (W * H * 3),
                edge / (FRAMES * (W - 1) * H * 3));
        return images;
    }

    static void preview(String preset, float[][][][]... runs) throws Exception {
        int scale = 3;
        var image =
                new java.awt.image.BufferedImage(
                        W * scale * runs.length,
                        H * scale * 2,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < runs.length; col++)
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) {
                        int color = 0;
                        for (int c = 0; c < 3; c++)
                            color =
                                    (color << 8)
                                            | (DisplayMapping.encode(
                                                            runs[col][row == 0 ? 9 : 15][c][y][x],
                                                            1)
                                                    & 255);
                        for (int yy = 0; yy < scale; yy++)
                            for (int xx = 0; xx < scale; xx++)
                                image.setRGB(
                                        (col * W + x) * scale + xx,
                                        (row * H + y) * scale + yy,
                                        color);
                    }
        javax.imageio.ImageIO.write(
                image, "png", new java.io.File("out/cli/p52-" + preset + "-seed" + seed + ".png"));
    }

    public static void main(String[] args) throws Exception {
        var presets = new ArrayList<String>();
        for (String arg : args)
            if (arg.startsWith("seed=")) seed = Long.parseLong(arg.substring(5));
            else presets.add(arg);
        if (presets.isEmpty()) presets.addAll(List.of("bounce-room", "glass"));
        System.out.println(
                "160x100 independent reference128 seed="
                        + (seed + 1000)
                        + ", 100 warmup/18 motion frames, seed="
                        + seed
                        + ", tile32, up to14 workers; no copy/display/AWT costs");
        System.out.println(
                "preset,mode,grid,requested_spp,cap_spp,mean_ms,p95_ms,mse,primary_paths,guide_rays,reverse_mse,cut_mse,horizontal_gradient_mse");
        for (String preset : presets) {
            var refs = references(preset);
            run(preset, "raw4", false, false, 4, 1, refs);
            var raw3 = run(preset, "raw3", false, false, 3, 1, refs);
            run(preset, "raw1", false, false, 1, 1, refs);
            var budget1 = run(preset, "budget1", true, true, 4, 1, refs);
            run(preset, "raw-low1", false, false, 1, .8, refs);
            var budgetLow = run(preset, "budget-low1", true, true, 4, .8, refs);
            preview(preset, raw3, budget1, budgetLow, refs);
        }
    }
}
