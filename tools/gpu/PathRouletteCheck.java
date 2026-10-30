package gpu;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.util.*;

/** Real-device expectation, independent sampling, history and publication checks. */
public final class PathRouletteCheck {
    private static final RenderView VIEW =
            new RenderView(
                    new Camera(
                            new Vec3(0, 0, 0),
                            new Rect(new Vec3(-1, -1, 1), new Vec3(2, 0, 0), new Vec3(0, 2, 0))),
                    64,
                    64);
    private static final RenderSettings SETTINGS =
            RenderSettings.defaults().withPathDepth(8).withSampleTarget(8).withSamplesPerBatch(1);

    public static void main(String[] args) throws Exception {
        settingsCopies();
        try (var kernel = new GpuKernel()) {
            if (args.length > 0) referenceAgreement(kernel, java.nio.file.Path.of(args[0]));
            unchangedShortPaths(kernel);
            expectation(kernel, false);
            expectation(kernel, true);
            history(kernel);
        }
        leases();
        require(
                !RenderCapabilities.CPU
                        .issues(room(false), VIEW, SETTINGS.withPathRoulette(true))
                        .isEmpty(),
                "CPU rejects unsupported roulette instead of silently ignoring it");
        System.out.println("Path roulette checks passed.");
    }

    private static void referenceAgreement(GpuKernel kernel, java.nio.file.Path shader) {
        try (var old = new GpuKernel(() -> {}, shader)) {
            var actual = new float[64 * 64 * 3];
            var expected = actual.clone();
            for (boolean glass : new boolean[] {false, true})
                for (int depth : new int[] {0, 2, 8}) {
                    var world = room(glass);
                    var settings = SETTINGS.withPathDepth(depth);
                    for (int sample = 0; sample < 32; sample++) {
                        old.trace(world, VIEW, settings, sample, expected);
                        kernel.trace(world, VIEW, settings, sample, actual);
                        if (!Arrays.equals(actual, expected))
                            throw new AssertionError("Default pixels changed");
                    }
                }
        }
        require(true, "default progressive pixels exactly match the pre-change shader");
    }

    private static WorldSnapshot room(boolean glass) {
        var surfaces = new ArrayList<SceneInstance>();
        surfaces.add(
                box("back", new Vec3(0, 0, 3), new Vec3(3, 3, .1f), new Vec3(.65f, .45f, .25f)));
        surfaces.add(
                box("left", new Vec3(-3, 0, 0), new Vec3(.1f, 3, 3), new Vec3(.45f, .65f, .25f)));
        surfaces.add(
                box("right", new Vec3(3, 0, 0), new Vec3(.1f, 3, 3), new Vec3(.25f, .45f, .65f)));
        surfaces.add(
                box("floor", new Vec3(0, -3, 0), new Vec3(3, .1f, 3), new Vec3(.65f, .65f, .65f)));
        if (glass)
            surfaces.add(
                    new SceneInstance(
                            "inside-glass",
                            BoxGeometry.UNIT,
                            new Transform(Vec3.ZERO, Vec3.ZERO, new Vec3(.4f, .4f, .4f)),
                            new Material(
                                    "glass",
                                    new Vec3(1, 1, 1),
                                    Material.Kind.DIELECTRIC,
                                    1.5f,
                                    Vec3.ZERO,
                                    0,
                                    Vec3.ZERO)));
        return new WorldSnapshot(
                0, surfaces, List.of(), List.of(), new Sky(new Vec3(1, 1, 1), new Vec3(1, 1, 1)));
    }

    private static SceneInstance box(String name, Vec3 position, Vec3 scale, Vec3 color) {
        return new SceneInstance(
                name,
                BoxGeometry.UNIT,
                new Transform(position, Vec3.ZERO, scale),
                new Material(name, color));
    }

    private static void unchangedShortPaths(GpuKernel kernel) {
        var raw = new float[64 * 64 * 3];
        var actual = raw.clone();
        for (int depth : new int[] {0, 1}) {
            for (int sample = 0; sample < 16; sample++) {
                // Recreate each prefix so the two settings cannot share an accumulator.
                for (int i = 0; i <= sample; i++)
                    kernel.trace(room(false), VIEW, SETTINGS.withPathDepth(depth), i, raw);
                for (int i = 0; i <= sample; i++)
                    kernel.trace(
                            room(false),
                            VIEW,
                            SETTINGS.withPathDepth(depth).withPathRoulette(true),
                            i,
                            actual);
                if (depth <= 1)
                    require(
                            Arrays.equals(raw, actual),
                            "unchanged short path depth " + depth + " sample " + sample);
            }
        }
        kernel.trace(room(false), VIEW, SETTINGS.withPathRoulette(true), 0, actual);
        var first = actual.clone();
        kernel.trace(room(true), VIEW, SETTINGS.withPathRoulette(true), 0, actual);
        kernel.trace(room(false), VIEW, SETTINGS.withPathRoulette(true), 0, actual);
        require(
                Arrays.equals(first, actual),
                "captured roulette samples reproduce after visiting another world");
        var glassRoom = room(true);
        var onlyGlass =
                new WorldSnapshot(
                        0,
                        List.of(glassRoom.instances().getLast()),
                        List.of(),
                        List.of(),
                        glassRoom.sky());
        for (int sample = 0; sample < 16; sample++)
            kernel.trace(onlyGlass, VIEW, SETTINGS, sample, raw);
        for (int sample = 0; sample < 16; sample++)
            kernel.trace(onlyGlass, VIEW, SETTINGS.withPathRoulette(true), sample, actual);
        require(
                Arrays.equals(raw, actual),
                "glass boundary crossings do not consume diffuse roulette events");
    }

    private static void expectation(GpuKernel kernel, boolean glass) {
        int seeds = 8, samples = 256;
        double[][] differences = new double[3][seeds];
        double[] referenceMean = new double[3];
        var world = room(glass);
        for (int seed = 0; seed < seeds; seed++) {
            double[][] means = new double[2][3];
            float[][] results = new float[2][64 * 64 * 3];
            for (int mode = 0; mode < 2; mode++) {
                var selected = SETTINGS.withSeed(seed + 1).withPathRoulette(mode == 1);
                for (int sample = 0; sample < samples; sample++)
                    kernel.trace(world, VIEW, selected, sample, results[mode]);
                for (int i = 0; i < results[mode].length; i++) {
                    float value = results[mode][i];
                    requireFinite(value);
                    means[mode][i % 3] += value / (64 * 64);
                }
            }
            require(
                    !Arrays.equals(results[0], results[1]),
                    "roulette changes the estimator, glass=" + glass + " seed=" + seed);
            for (int c = 0; c < 3; c++) {
                differences[c][seed] = means[1][c] - means[0][c];
                referenceMean[c] += means[0][c] / seeds;
            }
        }
        for (int c = 0; c < 3; c++) {
            double mean = Arrays.stream(differences[c]).average().orElseThrow(), variance = 0;
            for (double difference : differences[c])
                variance += (difference - mean) * (difference - mean) / (seeds - 1);
            double tolerance = 6 * Math.sqrt(variance / seeds) + .002 * referenceMean[c];
            System.out.printf(
                    Locale.ROOT,
                    "expectation glass=%s channel=%d reference=%.7f difference=%.7f"
                        + " tolerance=%.7f%n",
                    glass,
                    c,
                    referenceMean[c],
                    mean,
                    tolerance);
            require(
                    Math.abs(mean) < tolerance,
                    "matched-seed expected radiance, glass=" + glass + " channel=" + c);
        }
    }

    private static void history(GpuKernel kernel) {
        history(kernel, Denoiser.BUILT_IN);
        if (GpuNrdLibrary.available()) history(kernel, Denoiser.NRD_RELAX);
    }

    private static void history(GpuKernel kernel, Denoiser denoiser) {
        var world = room(false);
        var shown = new GpuKernel.Snapshot();
        var settings = SETTINGS.withTemporal(true, 1).withDenoiser(denoiser);
        kernel.trace(
                world,
                VIEW,
                settings,
                0,
                new GpuKernel.Destination(null, null, new GpuKernel.Snapshot(), 1, shown));
        var moved = ReconstructionBenchmark.moved(VIEW.camera(), 64, 64, 1);
        kernel.trace(
                world,
                moved,
                settings.withPathRoulette(true),
                0,
                new GpuKernel.Destination(null, null, new GpuKernel.Snapshot(), 1, shown));
        float[] toggled = shown.read(kernel.gpu).clone();
        kernel.temporal.reset();
        kernel.trace(
                world,
                moved,
                settings.withPathRoulette(true),
                0,
                new GpuKernel.Destination(null, null, new GpuKernel.Snapshot(), 1, shown));
        require(
                Arrays.equals(toggled, shown.read(kernel.gpu)),
                "roulette change discards incompatible " + denoiser + " history");
        shown.source.close();
    }

    private static void leases() throws Exception {
        var world = room(false);
        try (var session = new GpuRenderSession(world, VIEW, SETTINGS)) {
            var before = GpuCheck.waitImage(session, 8);
            try (var held = session.acquireImage()) {
                var next = SETTINGS.withPathRoulette(true);
                session.update(world, VIEW, next);
                var after = GpuCheck.waitImage(session, 8);
                require(
                        !Arrays.equals(before, after),
                        "roulette edit replaces the progressive estimator");
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++)
                        for (int c = 0; c < 3; c++)
                            if (held.rawPixels().value(c, x, y) != before[(y * 64 + x) * 3 + c])
                                throw new AssertionError("Overwritten lease");
                session.update(world, VIEW, next.withPathRoulette(false));
                session.update(world, VIEW, next);
                require(
                        Arrays.equals(after, GpuCheck.waitImage(session, 8)),
                        "rapid off/on resets and reproduces without stale accumulation");
                require(true, "held image remains immutable across roulette switches");
            }
        }
    }

    private static void settingsCopies() throws Exception {
        var selected = SETTINGS.withPathRoulette(true).withUpscaler(Upscaler.FSR_QUALITY);
        require(!RenderSettings.defaults().pathRoulette(), "reference path remains the default");
        for (var method : RenderSettings.class.getMethods()) {
            if (!method.getName().startsWith("with") || method.getName().equals("withPathRoulette"))
                continue;
            Object[] values = new Object[method.getParameterCount()];
            for (int i = 0; i < values.length; i++) {
                Class<?> type = method.getParameterTypes()[i];
                values[i] =
                        type == boolean.class
                                ? false
                                : type == int.class
                                        ? (method.getName().equals("withInteractive") ? 0 : 1)
                                        : type == long.class
                                                ? 1L
                                                : type == double.class
                                                        ? 1d
                                                        : type == Upscaler.class ? Upscaler.OFF : type == Denoiser.class
                                                                ? Denoiser.BUILT_IN
                                                                : type == Bloom.class
                                                                        ? Bloom.OFF
                                                                        : FoveationQuality
                                                                                .defaults();
            }
            var copied = (RenderSettings) method.invoke(selected, values);
            if (!method.getName().equals("withUpscaler")) {
                require(copied.upscaler() == Upscaler.FSR_QUALITY,
                        method.getName() + " preserves upscaler");
            }
            require(
                    copied.pathRoulette(),
                    method.getName() + " preserves roulette");
        }
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value) || value < 0)
            throw new AssertionError("Invalid radiance " + value);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
}
