package gpu;

import engine.*;
import engine.lights.DirectionalLight;
import engine.objects.Rect;

import game.GameNavigation;

import math.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * Real-device checks for continuous spacing, reconstruction boundaries and publication lifetime.
 */
public final class SmoothFoveationCheck {
    public static void main(String[] args) throws Exception {
        for (int size : new int[] {64, 65, 197, 1080, 1920}) checkAxis(size);
        System.out.println(
                "1080p smooth paths: "
                        + GpuSmoothFoveation.axis(1920).length
                                * GpuSmoothFoveation.axis(1080).length);
        require(
                (long) GpuSmoothFoveation.axis(1920).length * GpuSmoothFoveation.axis(1080).length
                        < 1920L * 1080 * .55,
                "bounded smooth ray count");
        var world = GpuCheck.landscape();
        var navigation = new GameNavigation();
        var settings = RenderSettings.defaults().withPathDepth(2);
        try (var kernel = new GpuKernel()) {
            for (int[] size : new int[][] {{64, 65}, {197, 131}, {65, 192}, {1920, 1080}}) {
                navigation.fixedResolution(size[0], size[1]);
                var view = navigation.view();
                float[] reference = new float[size[0] * size[1] * 3],
                        actual = new float[reference.length];
                kernel.trace(world, view, settings, 0, reference);
                var smooth = settings.withFoveated(true).withSmoothFoveation(true);
                kernel.trace(world, view, smooth, 0, actual);
                checkCenter(reference, actual, size[0], size[1]);
                float[] repeat = new float[actual.length];
                kernel.trace(world, view, smooth, 0, repeat);
                require(Arrays.equals(actual, repeat), "deterministic restart");
                kernel.trace(world, view, smooth.withFogDownsample(4), 0, repeat);
                require(Arrays.equals(actual, repeat), "full integration fallback");
                kernel.trace(world, view, smooth, 1, actual);
                kernel.trace(world, view, settings, 0, reference);
                kernel.trace(world, view, settings, 1, reference);
                checkCenter(reference, actual, size[0], size[1]);
                kernel.trace(world, view, settings.withSmoothFoveation(true), 0, actual);
                kernel.trace(world, view, settings, 0, reference);
                require(Arrays.equals(actual, reference), "smooth inactive without center detail");
                var lens = new RenderView(view.camera().withAperture(.02f), size[0], size[1]);
                kernel.trace(world, lens, settings, 0, reference);
                kernel.trace(world, lens, smooth, 0, actual);
                checkCenter(reference, actual, size[0], size[1]);
            }
            checkEdges(kernel);
            checkFog(kernel, world, navigation);
        }
        navigation.fixedResolution(197, 131);
        checkSession(world, navigation.view(), settings);
        System.out.println(
                "PASS smooth grid, exact core, accumulation, lens, fog, boundaries, toggles, packed"
                        + " pixels, leases and retirement");
    }

    private static void checkAxis(int size) {
        float[] axis = GpuSmoothFoveation.axis(size),
                inverse = GpuSmoothFoveation.inverse(axis, size);
        require(axis[0] == .5f && axis[axis.length - 1] == size - .5f, "edge coverage");
        for (int i = 1; i < axis.length; i++) {
            require(
                    axis[i] > axis[i - 1] && axis[i] - axis[i - 1] < 7,
                    "monotonic bounded spacing");
            require(Math.abs(axis[i] + axis[axis.length - 1 - i] - size) < .001, "symmetry");
        }
        for (int pixel = 0; pixel < size; pixel++) {
            int lower = (int) inverse[pixel], upper = Math.min(lower + 1, axis.length - 1);
            float t = inverse[pixel] - lower;
            require(
                    Math.abs(axis[lower] * (1 - t) + axis[upper] * t - (pixel + .5)) < .001,
                    "linear reconstruction covers every center");
        }
    }

    private static void checkCenter(float[] reference, float[] actual, int width, int height) {
        for (float value : actual)
            require(Float.isFinite(value) && value >= 0, "finite complete output");
        for (int y = height / 4 + 2; y < height * 3 / 4 - 2; y++)
            for (int x = width / 4 + 2; x < width * 3 / 4 - 2; x++)
                for (int c = 0; c < 3; c++) {
                    int index = (y * width + x) * 3 + c;
                    require(
                            reference[index] == actual[index],
                            "exact protected core at " + x + "," + y);
                }
    }

    private static void checkFog(GpuKernel kernel, WorldSnapshot world, GameNavigation navigation) {
        navigation.fixedResolution(197, 131);
        var light = new game.DayNightCycle().atmosphericLighting(Atmosphere.Parameters.clearAir());
        var foggy =
                new WorldSnapshot(
                        1,
                        world.instances(),
                        List.of(),
                        List.of(light.sun()),
                        light.sky().withFog(List.of(game.RuinsMist.region(0))));
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withFoveated(true)
                        .withSmoothFoveation(true);
        float[] full = new float[197 * 131 * 3], requestedQuarter = new float[full.length];
        kernel.trace(foggy, navigation.view(), settings, 0, full);
        kernel.trace(foggy, navigation.view(), settings.withFogDownsample(4), 0, requestedQuarter);
        require(
                kernel.fogDownsample == 1 && Arrays.equals(full, requestedQuarter),
                "atmosphere/fog uses full integration");
    }

    private static void checkEdges(GpuKernel kernel) {
        var objects =
                List.of(
                        new SceneInstance(
                                "red",
                                BoxGeometry.UNIT,
                                new Transform(
                                        new Vec3(1.4f, 0, 3),
                                        new Vec3(0, 25, 0),
                                        new Vec3(1, 2, 1)),
                                new Material("red", new Vec3(1, 0, 0))));
        var world =
                new WorldSnapshot(
                        0,
                        objects,
                        List.of(),
                        List.of(new DirectionalLight(new Vec3(-1, 2, -3), new Vec3(1, 1, 1), 1)),
                        new Sky(new Vec3(0, 0, 1), new Vec3(0, 0, 1)));
        var view =
                new RenderView(
                        new Camera(
                                new Vec3(0, 0, -2),
                                new Rect(
                                        new Vec3(-1, -1, 0), new Vec3(2, 0, 0), new Vec3(0, 2, 0))),
                        197,
                        131);
        float[] actual = new float[197 * 131 * 3];
        kernel.trace(
                world,
                view,
                RenderSettings.defaults().withFoveated(true).withSmoothFoveation(true),
                0,
                actual);
        int red = 0, blue = 0;
        for (int i = 0; i < actual.length; i += 3) {
            require(!(actual[i] > 0 && actual[i + 2] > 0), "no red surface/blue sky leakage");
            if (actual[i] > 0) red++;
            if (actual[i + 2] > 0) blue++;
        }
        require(red > 0 && blue > 0, "boundary test contains both surfaces");
    }

    private static void checkSession(WorldSnapshot world, RenderView view, RenderSettings settings)
            throws Exception {
        var session = new GpuRenderSession(world, view, settings.withFoveated(true));
        session.displayExposure(1);
        RenderImage held = null;
        try {
            float[] initial = GpuCheck.waitImage(session, 1);
            held = session.acquireImage();
            long generation = held.generation();
            float retained = held.rawPixels().value(0, 0, 0);
            for (boolean enabled : new boolean[] {true, false, true}) {
                session.update(
                        world, view, settings.withFoveated(true).withSmoothFoveation(enabled));
                float[] current = GpuCheck.waitImage(session, 1);
                try (var image = session.acquireImage()) {
                    require(image.generation() != generation && image.samples() == 1, "mode reset");
                    generation = image.generation();
                    int[] packed = new int[view.width() * view.height()];
                    image.presentationPixels().copyDisplayTo(packed, 1);
                    for (int y = 0; y < view.height(); y++)
                        for (int x = 0; x < view.width(); x++) {
                            int i = (y * view.width() + x) * 3;
                            require(
                                    packed[(view.height() - 1 - y) * view.width() + x]
                                            == DisplayMapping.pack(
                                                    current[i], current[i + 1], current[i + 2], 1),
                                    "raw/packed agreement");
                        }
                }
                if (!enabled)
                    require(Arrays.equals(initial, current), "fallback restores discrete mode");
                require(held.rawPixels().value(0, 0, 0) == retained, "retained lease");
            }
        } finally {
            session.close();
            require(session.awaitClosed(30), "owner retired");
            if (held != null) held.close();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
