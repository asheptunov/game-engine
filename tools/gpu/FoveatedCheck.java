package gpu;

import engine.*;

import game.*;

import math.Vec3;

import java.util.Arrays;
import java.util.List;

/** Real-device footprint coverage, exact center rays, accumulation and mode lifecycle. */
public final class FoveatedCheck {
    public static void main(String[] args) throws Exception {
        verifyFalloff();
        var navigation = new GameNavigation();
        navigation.fixedResolution(197, 131);
        navigation.pose(new Vec3(10.879049f, 2.7514815f, 22.884184f), 1.251, .079);
        var clock = new DayNightCycle();
        clock.hour(6.396);
        var light = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var world =
                new WorldSnapshot(
                        0,
                        AdventureWorld.create().instances(),
                        List.of(),
                        List.of(light.sun()),
                        light.sky().withFog(List.of(RuinsMist.region(0))));
        var settings = RenderSettings.defaults().withPathDepth(3);
        try (var kernel = new GpuKernel()) {
            for (int[] size :
                    new int[][] {{197, 131}, {64, 65}, {65, 192}, {192, 65}, {1920, 1080}}) {
                navigation.fixedResolution(size[0], size[1]);
                var view = navigation.view();
                int length = size[0] * size[1] * 3;
                float[] uniform = new float[length],
                        coarse = new float[length],
                        restored = new float[length];
                kernel.trace(world, view, settings, 0, uniform);
                kernel.trace(world, view, settings.withFoveated(true), 0, coarse);
                verifyPixels(uniform, coarse, size[0], size[1]);
                System.setProperty("gpu.sparseFoveation", "true");
                try {
                    kernel.trace(world, view, settings.withFoveated(true), 0, restored);
                } finally {
                    System.clearProperty("gpu.sparseFoveation");
                }
                require(Arrays.equals(coarse, restored), "compact/sparse exact ray agreement");
                kernel.trace(world, view, settings.withFoveated(true), 0, restored);
                require(Arrays.equals(coarse, restored), "deterministic restart");
                kernel.trace(
                        world, view, settings.withFoveated(true).withFogDownsample(4), 0, restored);
                require(
                        kernel.fogDownsample == 1 && Arrays.equals(coarse, restored),
                        "direct fog fallback");
                kernel.trace(world, view, settings, 0, restored);
                require(Arrays.equals(uniform, restored), "off restores all uniform pixels");
                kernel.trace(world, view, settings, 1, uniform);
                kernel.trace(world, view, settings.withFoveated(true), 0, coarse);
                kernel.trace(world, view, settings.withFoveated(true), 1, coarse);
                verifyPixels(uniform, coarse, size[0], size[1]);
            }
            navigation.fixedResolution(197, 131);
            var lens = new RenderView(navigation.view().camera().withAperture(.02f), 197, 131);
            var uniform = new float[197 * 131 * 3];
            var coarse = new float[uniform.length];
            kernel.trace(world, lens, settings, 0, uniform);
            kernel.trace(world, lens, settings.withFoveated(true), 0, coarse);
            verifyPixels(uniform, coarse, 197, 131);
        }
        var session = new GpuRenderSession(world, navigation.view(), settings);
        session.displayExposure(1);
        RenderImage held = null;
        float[] retained;
        try {
            var initial = GpuCheck.waitImage(session, 1);
            held = session.acquireImage();
            retained = read(held);
            for (boolean enabled : new boolean[] {true, false, true, false}) {
                session.update(world, navigation.view(), settings.withFoveated(enabled));
                var current = GpuCheck.waitImage(session, 1);
                try (var image = session.acquireImage()) {
                    require(image.samples() == 1, "mode change resets sample count");
                    require(
                            image.generation() != held.generation(),
                            "mode change invalidates generation");
                    var packed = new int[197 * 131];
                    image.presentationPixels().copyDisplayTo(packed, 1);
                    for (int y = 0; y < 131; y++)
                        for (int x = 0; x < 197; x++) {
                            int i = (y * 197 + x) * 3;
                            require(
                                    packed[(130 - y) * 197 + x]
                                            == DisplayMapping.pack(
                                                    current[i], current[i + 1], current[i + 2], 1),
                                    "raw/display equivalence");
                        }
                }
                if (!enabled)
                    require(Arrays.equals(initial, current), "session off restores reference");
                require(Arrays.equals(retained, read(held)), "toggle preserves held lease");
            }
            session.update(
                    world,
                    navigation.view(),
                    settings.withFoveated(true).withSampleTarget(2).withSamplesPerBatch(2));
            GpuCheck.waitImage(session, 2);
            session.update(world, navigation.view(), settings.withFoveated(false));
            session.update(world, navigation.view(), settings.withFoveated(true));
            GpuCheck.waitImage(session, 1);
        } finally {
            session.close();
            require(session.awaitClosed(30), "owner retirement");
        }
        require(Arrays.equals(retained, read(held)), "held raw survives close");
        held.close();
        System.out.println(
                "PASS foveation exact center, all footprint pixels, odd/thin grids, accumulation,"
                        + " lens, fog fallback, toggles, packed pixels and leases");
    }

    private static int rate(int x, int y, int width, int height) {
        return GpuFoveation.rate((x / 4) * 4, (y / 4) * 4, width, height);
    }

    private static void verifyFalloff() {
        int paths = 0;
        int[] total = new int[40], coarse = new int[40];
        for (int y = 0; y < 1080; y += 4) {
            for (int x = 0; x < 1920; x += 4) {
                double u = (x + 2.0) / 960 - 1, v = (y + 2.0) / 540 - 1;
                double radius = Math.sqrt(u * u + v * v);
                int rate = GpuFoveation.rate(x, y, 1920, 1080);
                require(rate == 1 || rate == 2 || rate == 4, "valid disjoint footprint");
                if (radius <= .5) require(rate == 1, "protected full-density core");
                if (radius >= .95) require(rate == 4, "bounded outer cost");
                paths += 16 / (rate * rate);
                int bin = (int) (radius * 40);
                if (bin < total.length) {
                    total[bin]++;
                    if (rate == (radius < .7 ? 2 : 4)) coarse[bin]++;
                }
            }
        }
        for (int bin = 20; bin < 38; bin++) {
            if (bin == 28 || bin == 29) continue;
            double radius = (bin + .5) / 40;
            double t = (radius - (bin < 28 ? .5 : .75)) / .2;
            double expected = t * t * (3 - 2 * t);
            require(
                    Math.abs((double) coarse[bin] / total[bin] - expected) < .08,
                    "radial density follows gradual probability");
        }
        require(paths > 655680 && paths < 2073600 * .42, "bounded extra ray budget");
        System.out.println("Gradual falloff primary paths at 1080p: " + paths);
    }

    private static void verifyPixels(float[] uniform, float[] coarse, int width, int height) {
        int changed = 0, center = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int rate = rate(x, y, width, height),
                        anchor = ((y / rate) * rate * width + (x / rate) * rate) * 3;
                int index = (y * width + x) * 3;
                for (int c = 0; c < 3; c++) {
                    require(
                            Float.isFinite(coarse[index + c]) && coarse[index + c] >= 0,
                            "finite radiance");
                    require(
                            coarse[index + c] == coarse[anchor + c],
                            "complete footprint including partial tiles");
                    if (rate == 1) {
                        require(coarse[index + c] == uniform[index + c], "exact center sample");
                        center++;
                    } else if (coarse[index + c] != uniform[index + c]) changed++;
                }
            }
        require(center > 0 && changed > 0, "dense center and changed periphery");
    }

    private static float[] read(RenderImage image) {
        var values = new float[image.width() * image.height() * 3];
        for (int y = 0; y < image.height(); y++)
            for (int x = 0; x < image.width(); x++)
                for (int c = 0; c < 3; c++)
                    values[(y * image.width() + x) * 3 + c] = image.rawPixels().value(c, x, y);
        return values;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
