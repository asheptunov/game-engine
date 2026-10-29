package gpu;

import engine.*;

import game.GameCapture;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Captured-camera full-output costs at 1440p and 4K; excludes Swing and monitor presentation. */
public final class HighResolutionBenchmark {
    public static void main(String[] args) throws Exception {
        var capture = GameCapture.load(Path.of(args[0]));
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        var snapshot = new GpuKernel.Snapshot();
        System.out.println(
                "width,height,round,mode,paths,medianGpuMs,p95GpuMs,medianHostMs,peakDeviceMiB");
        try (var kernel = new GpuKernel()) {
            for (int[] size : new int[][] {{2560, 1440}, {3840, 2160}}) {
                var view = new RenderView(shown.view().camera(), size[0], size[1]);
                int[] packed = new int[size[0] * size[1]];
                for (int round = 0; round < 3; round++)
                    for (String mode :
                            round % 2 == 0
                                    ? new String[] {"uniform", "default", "strong"}
                                    : new String[] {"strong", "default", "uniform"}) {
                        var quality =
                                mode.equals("strong")
                                        ? new FoveationQuality(.25, 12)
                                        : FoveationQuality.defaults();
                        var settings =
                                shown.settings()
                                        .withFoveated(!mode.equals("uniform"))
                                        .withSmoothFoveation(true)
                                        .withFoveationQuality(quality);
                        int paths =
                                mode.equals("uniform")
                                        ? size[0] * size[1]
                                        : GpuSmoothFoveation.axis(size[0], quality).length
                                                * GpuSmoothFoveation.axis(size[1], quality).length;
                        double[] gpu = new double[60], host = new double[60];
                        for (int i = -15; i < 60; i++) {
                            kernel.trace(
                                    world,
                                    view,
                                    settings,
                                    0,
                                    null,
                                    packed,
                                    snapshot,
                                    shown.exposure());
                            if (i >= 0) {
                                gpu[i] = kernel.executeNanos / 1e6;
                                host[i] = kernel.hostNanos / 1e6;
                            }
                        }
                        Arrays.sort(gpu);
                        Arrays.sort(host);
                        System.out.printf(
                                Locale.ROOT,
                                "%d,%d,%d,%s,%d,%.6f,%.6f,%.6f,%.3f%n",
                                size[0],
                                size[1],
                                round,
                                mode,
                                paths,
                                gpu[29],
                                gpu[56],
                                host[29],
                                kernel.gpu.peakBytes / 1048576.);
                    }
                float[] raw = new float[size[0] * size[1] * 3];
                var strong =
                        shown.settings()
                                .withFoveated(true)
                                .withSmoothFoveation(true)
                                .withFoveationQuality(new FoveationQuality(.25, 12));
                kernel.trace(world, view, strong, 0, raw);
                GpuCheck.image(raw, size[0], size[1], "strong-falloff-" + size[0] + "x" + size[1]);
            }
        }
    }
}
