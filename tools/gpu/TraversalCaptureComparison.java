package gpu;

import engine.DisplayMapping;
import engine.RenderView;

import game.GameCapture;

import java.nio.file.Path;
import java.util.Locale;

/** Compare original/confirmed traversal at 1080p; write sixteen-sample previews to out/gpu. */
public final class TraversalCaptureComparison {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Expected capture directory and image filename prefix");
        }
        var capture = GameCapture.load(Path.of(args[0]));
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        var view = new RenderView(shown.view().camera(), 1920, 1080);
        var expected = new float[view.width() * view.height() * 3];
        var actual = new float[expected.length];
        String previous = System.getProperty("gpu.referenceTraversal");
        try {
            System.setProperty("gpu.referenceTraversal", "true");
            try (var reference = new GpuKernel()) {
                System.setProperty("gpu.referenceTraversal", "false");
                try (var confirmed = new GpuKernel()) {
                    for (boolean smooth : new boolean[] {false, true}) {
                        var settings =
                                shown.settings().withFoveated(smooth).withSmoothFoveation(smooth);
                        for (int sample = 0; sample < 16; sample++) {
                            reference.trace(world, view, settings, sample, expected);
                            confirmed.trace(world, view, settings, sample, actual);
                            if (sample != 0 && sample != 15) continue;
                            compare(expected, actual, shown.exposure(), smooth, sample + 1);
                            if (sample == 15) {
                                // GpuCheck previews use fixed exposure 1, independent of capture
                                // exposure.
                                GpuCheck.image(
                                        expected,
                                        view.width(),
                                        view.height(),
                                        args[1] + "-reference-" + smooth);
                                GpuCheck.image(
                                        actual,
                                        view.width(),
                                        view.height(),
                                        args[1] + "-confirmed-" + smooth);
                            }
                        }
                    }
                }
            }
        } finally {
            if (previous == null) System.clearProperty("gpu.referenceTraversal");
            else System.setProperty("gpu.referenceTraversal", previous);
        }
    }

    private static void compare(
            float[] expected, float[] actual, float exposure, boolean smooth, int samples) {
        int changed = 0, displayChanged = 0, maximumDisplayError = 0;
        double squaredError = 0, maximumError = 0;
        for (int i = 0; i < expected.length; i++) {
            if (!Float.isFinite(expected[i]) || !Float.isFinite(actual[i])) {
                throw new AssertionError("Nonfinite pixel at channel " + i);
            }
            double difference = Math.abs(expected[i] - actual[i]);
            if (difference != 0) changed++;
            squaredError += difference * difference;
            maximumError = Math.max(maximumError, difference);
            int encodedExpected = Byte.toUnsignedInt(DisplayMapping.encode(expected[i], exposure));
            int encodedActual = Byte.toUnsignedInt(DisplayMapping.encode(actual[i], exposure));
            if (encodedExpected != encodedActual) displayChanged++;
            maximumDisplayError =
                    Math.max(maximumDisplayError, Math.abs(encodedExpected - encodedActual));
        }
        System.out.printf(
                Locale.ROOT,
                "smooth=%s spp=%d changedChannels=%d rawRmse=%.12g rawMax=%.12g displayChannels=%d"
                    + " displayMax=%d%n",
                smooth,
                samples,
                changed,
                Math.sqrt(squaredError / expected.length),
                maximumError,
                displayChanged,
                maximumDisplayError);
    }
}
