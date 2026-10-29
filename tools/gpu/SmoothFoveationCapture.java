package gpu;

import game.GameCapture;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Fixed captured-camera comparison; no live game or window is opened. */
public final class SmoothFoveationCapture {
    public static void main(String[] args) throws Exception {
        var capture = GameCapture.load(Path.of(args[0]));
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        var view = shown.view();
        var original = shown.settings().withSamplesPerBatch(1);
        float[] raw = new float[view.width() * view.height() * 3];
        int[] packed = new int[view.width() * view.height()];
        var snapshot = new GpuKernel.Snapshot();
        try (var kernel = new GpuKernel()) {
            System.out.println("round,mode,medianGpuMs,p95GpuMs,medianHostMs");
            for (int round = 0; round < (args.length > 1 ? 0 : 3); round++)
                for (String mode :
                        round % 2 == 0
                                ? new String[] {"uniform", "discrete", "smooth"}
                                : new String[] {"smooth", "discrete", "uniform"}) {
                    var settings =
                            original.withFoveated(!mode.equals("uniform"))
                                    .withSmoothFoveation(mode.equals("smooth"));
                    double[] gpu = new double[100], host = new double[100];
                    for (int i = -30; i < 100; i++) {
                        kernel.trace(
                                world, view, settings, 0, null, packed, snapshot, shown.exposure());
                        if (i >= 0) {
                            gpu[i] = kernel.executeNanos / 1e6;
                            host[i] = kernel.hostNanos / 1e6;
                        }
                    }
                    Arrays.sort(gpu);
                    Arrays.sort(host);
                    System.out.printf(
                            Locale.ROOT,
                            "%d,%s,%.6f,%.6f,%.6f%n",
                            round,
                            mode,
                            gpu[49],
                            gpu[94],
                            host[49]);
                }
            for (String mode : new String[] {"uniform", "discrete", "smooth"}) {
                var settings =
                        original.withFoveated(!mode.equals("uniform"))
                                .withSmoothFoveation(mode.equals("smooth"));
                for (int sample = 0; sample < 32; sample++) {
                    kernel.trace(world, view, settings, sample, raw);
                    if (sample == 0 || sample == 31)
                        GpuCheck.image(
                                raw,
                                view.width(),
                                view.height(),
                                "smooth-" + mode + "-" + (sample + 1));
                }
            }
        }
    }
}
