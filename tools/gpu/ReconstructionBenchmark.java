package gpu;

import engine.*;

import game.GameCapture;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Same-grid camera-motion quality and GPU cost; excludes window submission and simulation. */
public final class ReconstructionBenchmark {
    public static void main(String[] args) throws Exception {
        var capture = GameCapture.load(Path.of(args[0]));
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        var camera = shown.view().camera().withAperture(0);
        var settings =
                shown.settings()
                        .withFoveated(false)
                        .withFogDownsample(1)
                        .withInteractive(false, 8.333, 0, 0)
                        .withSamplesPerBatch(1)
                        .withBloom(Bloom.OFF);
        var directory = Path.of(args[1]);
        Files.createDirectories(directory);
        try (var kernel = new GpuKernel()) {
            System.out.println("device=" + kernel.gpu.description);
            System.out.println("camera=" + camera + " settings=" + settings);
            var snapshot = new GpuKernel.Snapshot();
            var presentation = new GpuKernel.Snapshot();
            int costWidth = args.length > 2 ? Integer.parseInt(args[2]) : 1920;
            int costHeight = costWidth * 9 / 16;
            int[] packed = new int[costWidth * costHeight];
            System.out.println("costGrid=" + costWidth + "x" + costHeight);
            System.out.println(
                    "round,temporal,medianGpuMs,p95GpuMs,medianReconstructionMs,medianHostMs,peakMiB");
            try (var csv = Files.newBufferedWriter(directory.resolve("timings.csv"))) {
                csv.write("round,temporal,frame,gpuMs,reconstructionMs,hostMs\n");
                for (int round = 0; round < 3; round++)
                    for (boolean temporal : new boolean[] {round % 2 == 0, round % 2 != 0}) {
                        double[] gpu = new double[60],
                                reconstruction = new double[60],
                                host = new double[60];
                        for (int frame = -20; frame < 60; frame++) {
                            var view = moved(camera, costWidth, costHeight, frame + 20);
                            kernel.trace(
                                    world,
                                    view,
                                    settings.withTemporal(temporal, 0),
                                    0,
                                    new GpuKernel.Destination(
                                            null,
                                            packed,
                                            snapshot,
                                            1,
                                            temporal ? presentation : null));
                            if (frame < 0) continue;
                            gpu[frame] = kernel.executeNanos / 1e6;
                            reconstruction[frame] = kernel.reconstructionNanos / 1e6;
                            host[frame] = kernel.hostNanos / 1e6;
                            csv.write(
                                    String.format(
                                            Locale.ROOT,
                                            "%d,%s,%d,%.6f,%.6f,%.6f%n",
                                            round,
                                            temporal,
                                            frame,
                                            gpu[frame],
                                            reconstruction[frame],
                                            host[frame]));
                        }
                        Arrays.sort(gpu);
                        Arrays.sort(reconstruction);
                        Arrays.sort(host);
                        System.out.printf(
                                Locale.ROOT,
                                "%d,%s,%.4f,%.4f,%.4f,%.4f,%.1f%n",
                                round,
                                temporal,
                                gpu[29],
                                gpu[56],
                                reconstruction[29],
                                host[29],
                                kernel.gpu.peakBytes / 1048576.0);
                    }
            }
            if (Boolean.getBoolean("gpu.benchmarkCostOnly")) return;
            int width = 384, height = 216, frames = 24;
            float[][] raw = new float[frames][],
                    filtered = new float[frames][],
                    spatial = new float[frames][];
            kernel.temporal.reset();
            for (int frame = 0; frame < frames; frame++) {
                raw[frame] = new float[width * height * 3];
                kernel.trace(
                        world,
                        moved(camera, width, height, frame),
                        settings.withTemporal(true, 0),
                        0,
                        new GpuKernel.Destination(raw[frame], null, null, 1, presentation));
                filtered[frame] = presentation.read(kernel.gpu).clone();
            }
            for (int frame = 0; frame < frames; frame++) {
                kernel.temporal.reset();
                kernel.trace(
                        world,
                        moved(camera, width, height, frame),
                        settings.withTemporal(true, 0),
                        0,
                        new GpuKernel.Destination(
                                new float[width * height * 3], null, null, 1, presentation));
                spatial[frame] = presentation.read(kernel.gpu).clone();
            }
            double rawError = 0, filteredError = 0, spatialError = 0;
            try (var csv = Files.newBufferedWriter(directory.resolve("quality.csv"))) {
                csv.write("frame,rawMse,reconstructedMse,spatialOnlyMse\n");
                for (int frame = 0; frame < frames; frame++) {
                    float[] reference = new float[width * height * 3];
                    for (int sample = 0; sample < 128; sample++)
                        kernel.trace(
                                world,
                                moved(camera, width, height, frame),
                                settings.withTemporal(false, 0),
                                sample,
                                reference);
                    double a = error(raw[frame], reference), b = error(filtered[frame], reference);
                    double c = error(spatial[frame], reference);
                    rawError += a;
                    filteredError += b;
                    spatialError += c;
                    csv.write(frame + "," + a + "," + b + "," + c + "\n");
                    if (frame == 0 || frame == 12 || frame == 23) {
                        save(directory.resolve("raw-" + frame + ".png"), raw[frame], width, height);
                        save(
                                directory.resolve("temporal-" + frame + ".png"),
                                filtered[frame],
                                width,
                                height);
                        save(
                                directory.resolve("reference-" + frame + ".png"),
                                reference,
                                width,
                                height);
                        save(
                                directory.resolve("spatial-" + frame + ".png"),
                                spatial[frame],
                                width,
                                height);
                    }
                }
            }
            System.out.printf(
                    Locale.ROOT,
                    "motion display-space MSE: raw %.8f, temporal %.8f, reduction %.2f%%%n",
                    rawError / frames,
                    filteredError / frames,
                    (1 - filteredError / rawError) * 100);
            System.out.printf(
                    Locale.ROOT,
                    "spatial-only MSE %.8f, temporal improvement over spatial %.2f%%%n",
                    spatialError / frames,
                    (1 - filteredError / spatialError) * 100);
        }
    }

    static RenderView moved(Camera camera, int width, int height, int frame) {
        Vec3 offset = new Vec3(frame * .012f, 0, frame * .004f);
        var sensor = camera.sensor();
        return new RenderView(
                new Camera(
                        camera.eye().add(offset),
                        new engine.objects.Rect(
                                sensor.origin().add(offset), sensor.edge1(), sensor.edge2())),
                width,
                height);
    }

    static double error(float[] a, float[] b) {
        double result = 0;
        for (int i = 0; i < a.length; i++) {
            double delta = mapped(a[i]) - mapped(b[i]);
            result += delta * delta;
        }
        return result / a.length;
    }

    private static double mapped(float x) {
        x = Math.max(0, x);
        return Math.pow(x / (1 + x), 1 / 2.2);
    }

    static void save(Path path, float[] values, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        DisplayCopy.interleaved(values, pixels, width, height, 1);
        ImageIO.write(image, "png", path.toFile());
    }
}
