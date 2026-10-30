package gpu;

import engine.*;
import engine.objects.Rect;

import game.DayNightCycle;

import math.Vec3;

import java.nio.file.*;
import java.util.*;

/** Paced frozen-geometry sunlight sequence: cadence, history reset control, and reference error. */
public final class SunMotionBenchmark {
    private static final int WIDTH = 256, HEIGHT = 192, FRAMES = 96;
    private static final RenderView VIEW =
            new RenderView(
                    new Camera(
                            new Vec3(0, 1, -5),
                            new Rect(new Vec3(-1, 0, -3), new Vec3(2, 0, 0), new Vec3(0, 2, 0))),
                    WIDTH,
                    HEIGHT);
    private static final WorldSnapshot BASE = ReconstructionCheck.world(0, false, false);
    private static final String[] MODES = {"stepped", "smooth-reset", "smooth-history"};

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        double daySeconds = args.length > 1 ? Double.parseDouble(args[1]) : 30;
        var denoiser =
                args.length > 2 && args[2].equals("builtin")
                        ? Denoiser.BUILT_IN
                        : Denoiser.NRD_RELAX;
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(4)
                        .withTemporal(true, 1)
                        .withDenoiser(denoiser);
        var results = new LinkedHashMap<String, List<float[]>>();
        for (String mode : MODES) {
            var values = new ArrayList<float[]>();
            results.put(mode, values);
            var shown = new GpuKernel.Snapshot();
            var raw = new float[WIDTH * HEIGHT * 3];
            try (var kernel = new GpuKernel()) {
                kernel.trace(
                        world(0, daySeconds, !mode.equals("stepped")),
                        VIEW,
                        settings,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, shown));
                kernel.temporal.reset();
                long start = System.nanoTime();
                for (int frame = 0; frame < FRAMES; frame++) {
                    double seconds = frame / 60.;
                    boolean stepped = mode.equals("stepped");
                    if (mode.equals("smooth-reset")) kernel.temporal.reset();
                    var world = world(stepped ? (frame / 15) * .25 : seconds, daySeconds, !stepped);
                    int sample = stepped ? frame % 15 : 0;
                    kernel.trace(
                            world,
                            VIEW,
                            settings,
                            sample,
                            new GpuKernel.Destination(raw, null, null, 1, shown));
                    values.add(shown.read(kernel.gpu).clone());
                    long remaining = start + Math.round((frame + 1) * 1e9 / 60) - System.nanoTime();
                    if (remaining > 0)
                        Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
                }
                shown.source.close();
            }
        }
        try (var kernel = new GpuKernel();
                var csv = Files.newBufferedWriter(output.resolve("quality.csv"))) {
            csv.write(
                    "frame,seconds,steppedMse,resetMse,historyMse,steppedResidualChange,resetResidualChange,historyResidualChange\n");
            var referenceSettings = settings.withTemporal(false, 2);
            // NRD uses pixel-center primaries, so retain its guide specialization for references.
            if (denoiser == Denoiser.NRD_RELAX) referenceSettings = settings;
            double[] errors = new double[3], changes = new double[3];
            float[][] previousResidual = new float[3][];
            int count = 0;
            for (int frame = 0; frame < FRAMES; frame += 3) {
                float[] reference = new float[WIDTH * HEIGHT * 3];
                var referenceWorld = world(frame / 60., daySeconds, true);
                var scratch = new GpuKernel.Snapshot();
                for (int sample = 0; sample < 128; sample++)
                    kernel.trace(
                            referenceWorld,
                            VIEW,
                            referenceSettings,
                            sample,
                            new GpuKernel.Destination(
                                    reference,
                                    null,
                                    null,
                                    1,
                                    denoiser == Denoiser.NRD_RELAX ? scratch : null));
                if (scratch.source != null) scratch.source.close();
                csv.write(frame + "," + frame / 60.);
                double[] delta = new double[3];
                for (int mode = 0; mode < MODES.length; mode++) {
                    var value = results.get(MODES[mode]).get(frame);
                    double error = ReconstructionBenchmark.error(value, reference);
                    errors[mode] += error;
                    csv.write("," + error);
                    var residual = new float[value.length];
                    for (int i = 0; i < value.length; i++) {
                        residual[i] = mapped(value[i]) - mapped(reference[i]);
                        if (count > 0)
                            delta[mode] +=
                                    Math.pow(residual[i] - previousResidual[mode][i], 2)
                                            / residual.length;
                    }
                    previousResidual[mode] = residual;
                    changes[mode] += delta[mode];
                    if (frame == 12 || frame == 15 || frame == 18 || frame == 45 || frame == 93)
                        ReconstructionBenchmark.save(
                                output.resolve(MODES[mode] + "-" + frame + ".png"),
                                value,
                                WIDTH,
                                HEIGHT);
                }
                for (double value : delta) csv.write("," + value);
                csv.write("\n");
                if (frame == 12 || frame == 15 || frame == 18 || frame == 45 || frame == 93)
                    ReconstructionBenchmark.save(
                            output.resolve("reference-" + frame + ".png"),
                            reference,
                            WIDTH,
                            HEIGHT);
                count++;
            }
            for (int mode = 0; mode < MODES.length; mode++)
                System.out.printf(
                        Locale.ROOT,
                        "%s,%s,daySeconds=%.1f,meanMse=%.8f,meanResidualChange=%.8f%n",
                        denoiser,
                        MODES[mode],
                        daySeconds,
                        errors[mode] / count,
                        changes[mode] / (count - 1));
        }
    }

    private static float mapped(float value) {
        double positive = Math.max(0, value);
        return (float) Math.pow(positive / (1 + positive), 1 / 2.2);
    }

    private static WorldSnapshot world(double seconds, double daySeconds, boolean tagged) {
        var clock = new DayNightCycle();
        clock.hour(9 + seconds * 24 / daySeconds);
        var lighting = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        return new WorldSnapshot(
                0,
                BASE.instances(),
                BASE.legacyObjects(),
                List.of(lighting.sun()),
                lighting.sky(),
                tagged ? new LightingContinuity(1, 0, seconds) : LightingContinuity.NONE);
    }
}
