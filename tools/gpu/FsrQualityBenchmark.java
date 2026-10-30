package gpu;

import engine.*;
import engine.objects.Rect;

import game.LightingQualityScene;

import math.Vec3;

import java.nio.file.*;
import java.util.*;

/** Matched low-sample combat and subpixel-edge sequences against native 128-sample references. */
public final class FsrQualityBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        if (args.length < 2) compare(output.resolve("combat"), LightingQualityScene.frames(), 1, 8);
        var camera =
                new Camera(
                        Vec3.ZERO,
                        new Rect(
                                new Vec3(-1, -.5625f, 1),
                                new Vec3(2, 0, 0),
                                new Vec3(0, 1.125f, 0)));
        var objects = new ArrayList<SceneInstance>();
        objects.add(
                new SceneInstance(
                        "wall",
                        BoxGeometry.UNIT,
                        new Transform(new Vec3(0, 0, 10), Vec3.ZERO, new Vec3(10, 10, .05f)),
                        new Material("white", new Vec3(1, 1, 1))));
        for (int i = 0; i < 72; i++)
            objects.add(
                    new SceneInstance(
                            "bar" + i,
                            BoxGeometry.UNIT,
                            new Transform(
                                    new Vec3(-4 + i * .11f, 0, 9.8f),
                                    new Vec3(0, 0, i % 3 == 0 ? 9 : 0),
                                    new Vec3(.01f, 1.8f, .01f)),
                            new Material("black", Vec3.ZERO)));
        var world =
                new WorldSnapshot(
                        0,
                        objects,
                        List.of(),
                        List.of(),
                        new Sky(new Vec3(1, 1, 1), new Vec3(1, 1, 1)));
        var frames = new ArrayList<LightingQualityScene.Frame>();
        for (int i = 0; i < 48; i++) {
            var delta = new Vec3(i * .002f, 0, 0);
            var moved =
                    new Camera(
                            camera.eye().add(delta),
                            new Rect(
                                    camera.sensor().origin().add(delta),
                                    camera.sensor().edge1(),
                                    camera.sensor().edge2()));
            frames.add(new LightingQualityScene.Frame(world, new RenderView(moved, 512, 288)));
        }
        compare(output.resolve("thin-motion"), frames, 1, 1);
        compare(output.resolve("thin-static"), List.of(frames.get(0)), 32, 1);
    }

    private static void compare(
            Path output, List<LightingQualityScene.Frame> frames, int samples, int depth)
            throws Exception {
        Files.createDirectories(output);
        var images = new LinkedHashMap<String, List<float[]>>();
        for (String mode : List.of("half", "split", "fsr-performance", "fsr-quality")) {
            var settings =
                    RenderSettings.defaults()
                            .withPathDepth(depth)
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
            var list = new ArrayList<float[]>();
            images.put(mode, list);
            try (var kernel = new GpuKernel()) {
                var raw = new GpuKernel.Snapshot();
                var shown = new GpuKernel.Snapshot();
                for (int i = -1; i < frames.size(); i++) {
                    var frame = frames.get(Math.max(0, i));
                    var destination =
                            mode.equals("half")
                                    ? new RenderView(frame.view().camera(), 256, 144)
                                    : frame.view();
                    var input = GpuLighting.sampled(destination, settings);
                    for (int sample = 0; sample < samples; sample++)
                        kernel.trace(
                                frame.world(),
                                input,
                                settings,
                                sample,
                                new GpuKernel.Destination(null, null, raw, 1, shown, destination));
                    if (i < 0) {
                        kernel.temporal.reset();
                        continue;
                    }
                    var pixels = shown.read(kernel.gpu).clone();
                    list.add(mode.equals("half") ? enlarge(pixels) : pixels);
                }
            }
        }
        double[] totals = new double[images.size()];
        try (var kernel = new GpuKernel();
                var csv = Files.newBufferedWriter(output.resolve("quality.csv"))) {
            csv.write("frame," + String.join(",", images.keySet()) + "\n");
            for (int i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                var reference = new float[512 * 288 * 3];
                for (int sample = 0; sample < 128; sample++)
                    kernel.trace(
                            frame.world(),
                            frame.view(),
                            RenderSettings.defaults().withPathDepth(depth),
                            sample,
                            reference);
                csv.write(Integer.toString(i));
                int modeIndex = 0;
                for (var entry : images.entrySet()) {
                    var pixels = entry.getValue().get(i);
                    double error = ReconstructionBenchmark.error(pixels, reference);
                    totals[modeIndex++] += error;
                    csv.write("," + error);
                    if (i == 0 || i == frames.size() - 1 || i == 7)
                        ReconstructionBenchmark.save(
                                output.resolve(entry.getKey() + "-" + i + ".png"),
                                pixels,
                                512,
                                288);
                }
                csv.write("\n");
                if (i == 0 || i == frames.size() - 1 || i == 7)
                    ReconstructionBenchmark.save(
                            output.resolve("reference-" + i + ".png"), reference, 512, 288);
            }
        }
        int index = 0;
        for (String mode : images.keySet())
            System.out.printf(
                    Locale.ROOT,
                    "%s,%s,%.8f%n",
                    output.getFileName(),
                    mode,
                    totals[index++] / frames.size());
    }

    private static float[] enlarge(float[] source) {
        var result = new float[512 * 288 * 3];
        for (int y = 0; y < 288; y++)
            for (int x = 0; x < 512; x++)
                for (int c = 0; c < 3; c++)
                    result[(y * 512 + x) * 3 + c] = source[((y / 2) * 256 + x / 2) * 3 + c];
        return result;
    }
}
