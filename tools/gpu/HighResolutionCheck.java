package gpu;

import engine.*;

import game.*;

import java.util.Arrays;

import javax.swing.*;

/** Real-device 1440p/4K dispatch, sampling control and image ownership checks. */
public final class HighResolutionCheck {
    public static void main(String[] args) throws Exception {
        checkLayouts();
        checkControls();
        var nav = new GameNavigation();
        var world = GpuCheck.landscape();
        var uniform = RenderSettings.defaults();
        var snapshot = new GpuKernel.Snapshot();
        try (var kernel = new GpuKernel()) {
            for (int[] size : new int[][] {{2560, 1440}, {3840, 2160}}) {
                nav.fixedResolution(size[0], size[1]);
                var view = nav.view();
                float[] reference = new float[size[0] * size[1] * 3],
                        actual = new float[reference.length];
                int[] packed = new int[size[0] * size[1]];
                kernel.trace(world, view, uniform, 0, reference);
                for (var quality :
                        new FoveationQuality[] {
                            FoveationQuality.defaults(),
                            new FoveationQuality(.25, 12),
                            new FoveationQuality(.75, 2),
                            new FoveationQuality(.1, 16)
                        }) {
                    var settings =
                            uniform.withFoveated(true)
                                    .withSmoothFoveation(true)
                                    .withFoveationQuality(quality);
                    kernel.trace(world, view, settings, 0, actual);
                    kernel.trace(world, view, settings, 0, null, packed, snapshot, 1);
                    checkImage(reference, actual, packed, size[0], size[1]);
                }
                var discrete = uniform.withFoveated(true);
                kernel.trace(world, view, discrete, 0, actual);
                System.setProperty("gpu.sparseFoveation", "true");
                try {
                    kernel.trace(world, view, discrete, 0, reference);
                } finally {
                    System.clearProperty("gpu.sparseFoveation");
                }
                require(
                        Arrays.equals(reference, actual),
                        "12-bit packed locations match sparse reference");
                System.out.println(
                        "PASS resolution "
                                + size[0]
                                + "x"
                                + size[1]
                                + " all control extremes, raw/packed and discrete coordinates");
            }
            System.out.println("Peak device bytes in check: " + kernel.gpu.peakBytes);
        }
        nav.fixedResolution(197, 131);
        checkLiveChanges(world, nav.view());
    }

    private static void checkLayouts() {
        for (int size : new int[] {64, 65, 2560, 3840})
            for (var quality :
                    new FoveationQuality[] {
                        new FoveationQuality(.1, 16),
                        new FoveationQuality(.75, 2),
                        FoveationQuality.defaults()
                    }) {
                float[] axis = GpuSmoothFoveation.axis(size, quality);
                require(axis[0] == .5f && axis[axis.length - 1] == size - .5f, "endpoint coverage");
                for (int i = 1; i < axis.length; i++)
                    require(axis[i] > axis[i - 1], "ordered samples");
            }
        for (int count : new int[] {1, 65535 * 64, 65535 * 64 + 1, 3840 * 2160, 3840 * 3840}) {
            int x = GpuDispatch.columns(count), y = GpuDispatch.rows(count);
            require(
                    x <= 65535 && y <= 65535 && (long) x * y * 64 >= count,
                    "portable dispatch coverage");
        }
        int ordinary = GpuSmoothFoveation.axis(3840).length * GpuSmoothFoveation.axis(2160).length;
        var strong = new FoveationQuality(.25, 12);
        int reduced =
                GpuSmoothFoveation.axis(3840, strong).length
                        * GpuSmoothFoveation.axis(2160, strong).length;
        require(reduced < ordinary, "strong falloff reduces rays");
        System.out.println("4K paths default=" + ordinary + " strong=" + reduced);
        for (double center : new double[] {Double.NaN, Double.POSITIVE_INFINITY, .09, .76}) {
            try {
                new FoveationQuality(center, 6);
                throw new AssertionError("accepted invalid center");
            } catch (IllegalArgumentException expected) {
            }
        }
        for (int edge : new int[] {1, 17}) {
            try {
                new FoveationQuality(.5, edge);
                throw new AssertionError("accepted invalid spacing");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    private static void checkImage(
            float[] reference, float[] actual, int[] packed, int width, int height) {
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 3;
                for (int c = 0; c < 3; c++)
                    require(Float.isFinite(actual[i + c]) && actual[i + c] >= 0, "finite output");
                require(
                        packed[(height - 1 - y) * width + x]
                                == DisplayMapping.pack(actual[i], actual[i + 1], actual[i + 2], 1),
                        "packed tail/rows equal raw");
                if (Math.abs(x - width / 2) < 16 && Math.abs(y - height / 2) < 16)
                    for (int c = 0; c < 3; c++)
                        require(actual[i + c] == reference[i + c], "protected aiming center");
            }
    }

    private static void checkLiveChanges(WorldSnapshot world, RenderView view) throws Exception {
        var original = RenderSettings.defaults().withFoveated(true).withSmoothFoveation(true);
        var session = new GpuRenderSession(world, view, original);
        RenderImage held = null;
        try {
            var initial = GpuCheck.waitImage(session, 1);
            held = session.acquireImage();
            long generation = held.generation();
            for (var quality :
                    new FoveationQuality[] {
                        new FoveationQuality(.25, 12),
                        new FoveationQuality(.1, 16),
                        FoveationQuality.defaults()
                    }) {
                session.update(world, view, original.withFoveationQuality(quality));
                var current = GpuCheck.waitImage(session, 1);
                try (var image = session.acquireImage()) {
                    require(
                            image.generation() != generation && image.samples() == 1,
                            "quality edit resets generation/samples");
                    generation = image.generation();
                }
                require(
                        held.rawPixels().value(0, 0, 0) == initial[0],
                        "held lease survives quality edit");
                if (quality.equals(FoveationQuality.defaults()))
                    require(
                            Arrays.equals(initial, current),
                            "default restores exact original grid");
            }
        } finally {
            session.close();
            require(session.awaitClosed(30), "owner retired");
            if (held != null) held.close();
        }
        System.out.println("PASS live quality changes, default reproduction and leases");
    }

    private static void checkControls() throws Exception {
        var nav = new GameNavigation();
        nav.fixedResolution(96, 64);
        try (var renderer = new GameRenderer(nav, BlockWorld.landscape(), "gpu")) {
            renderer.configure(s -> s.withPaused(true));
            SwingUtilities.invokeAndWait(
                    () -> {
                        var panel = new GameSettingsPanel(nav, renderer);
                        var center = (JSlider) find(panel, "Sharp center (%)");
                        var edge = (JSlider) find(panel, "Edge spacing (pixels)");
                        center.setValueIsAdjusting(true);
                        center.setValue(25);
                        require(
                                renderer.settings().foveationQuality().centerFraction() == .5,
                                "drag defers allocation");
                        panel.refresh();
                        require(center.getValue() == 25, "refresh preserves active drag");
                        center.setValueIsAdjusting(false);
                        edge.setValue(12);
                        require(
                                renderer.settings()
                                        .foveationQuality()
                                        .equals(new FoveationQuality(.25, 12)),
                                "live slider values");
                        var saved = renderer.settings();
                        panel.refresh();
                        require(renderer.settings().equals(saved), "refresh is read-only");
                        ((JButton) find(panel, "4K strong falloff")).doClick();
                        require(
                                nav.view().width() == 3840 && nav.view().height() == 2160,
                                "4K shortcut dimensions");
                        require(
                                renderer.settings().foveated()
                                        && renderer.settings().smoothFoveation()
                                        && !renderer.settings().interactive(),
                                "4K shortcut fixed smooth mode");
                    });
        }
        System.out.println("PASS live slider UI and 4K shortcut");
    }

    private static java.awt.Component find(java.awt.Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName()) && !(child instanceof JPanel)) return child;
            if (child instanceof java.awt.Container container) {
                var found = find(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
