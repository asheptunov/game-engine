package gpu;

import engine.*;

import math.Vec3;

import java.util.*;

/** Device checks for independent raw/output grids, native visibility, fallback and leases. */
public final class LightingReconstructionCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    private static RenderImage await(GpuRenderSession session) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null && image.generation() == session.progress(null).requestedGeneration())
                return image;
            if (image != null) image.close();
            Thread.sleep(1);
        }
        throw new AssertionError(session.status());
    }

    private static float[] copy(RgbPixels source) {
        var values = new float[source.width() * source.height() * 3];
        for (int y = 0; y < source.height(); y++)
            for (int x = 0; x < source.width(); x++)
                for (int c = 0; c < 3; c++)
                    values[(y * source.width() + x) * 3 + c] = source.value(c, x, y);
        return values;
    }

    public static void main(String[] args) throws Exception {
        var world = ReconstructionCheck.world(0, false, false);
        var camera =
                new Camera(
                        new Vec3(0, 1, -5),
                        new engine.objects.Rect(
                                new Vec3(-1, 0, -3), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
        var output = new RenderView(camera, 191, 159);
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(3)
                        .withTemporal(true, 1)
                        .withLightingScale(.5);
        var input = GpuLighting.sampled(output, settings);
        require(
                input.width() == 96 && input.height() == 80,
                "odd native grid retains rounded independent lighting grid");
        controls(output, settings);
        try (var kernel = new GpuKernel();
                var reference = new GpuKernel()) {
            var raw = new GpuKernel.Snapshot();
            var shown = new GpuKernel.Snapshot();
            var nativeDisplay = new int[output.width() * output.height()];
            var expected = new float[input.width() * input.height() * 3];
            for (int sample = 0; sample < 4; sample++) {
                kernel.trace(
                        world,
                        input,
                        settings,
                        sample,
                        new GpuKernel.Destination(null, nativeDisplay, raw, 1, shown, output));
                reference.trace(world, input, settings.withLightingScale(1), sample, expected);
                require(
                        Arrays.equals(expected, raw.read(kernel.gpu)),
                        "raw lighting estimator exact at " + (sample + 1) + " spp");
                var pixels = shown.read(kernel.gpu);
                require(
                        pixels.length == output.width() * output.height() * 3,
                        "presentation has native dimensions");
                for (float value : pixels)
                    if (!Float.isFinite(value) || value < 0)
                        throw new AssertionError("Invalid radiance");
                int[] encoded = new int[nativeDisplay.length];
                DisplayCopy.interleaved(pixels, encoded, output.width(), output.height(), 1);
                require(
                        Arrays.equals(encoded, nativeDisplay),
                        "packed pixels match native reconstructed snapshot");
                require(
                        kernel.fogReusedPixels == 0,
                        "native lighting reuse does not inflate fog-pass counters");
            }
            visibility(kernel, camera, settings);
            sky(kernel, camera, settings);
            largeFallbackQueue(kernel, camera, settings);
        }
        leases(world, output, settings, false);
        leases(world, output, settings, true);
        System.out.println("Native visibility / reduced lighting checks passed.");
    }

    private static void controls(RenderView output, RenderSettings settings) {
        for (var fallback :
                List.of(
                        settings.withTemporal(false, 2),
                        settings.withFoveated(true),
                        settings.withPathDepth(0),
                        settings.withFogDownsample(2),
                        settings.withInteractive(true, 8.333, 64, 64))) {
            require(
                    GpuLighting.sampled(output, fallback).equals(output),
                    "unsupported combination retains requested raw grid");
        }
        var lens =
                new RenderView(output.camera().withAperture(.1f), output.width(), output.height());
        require(
                GpuLighting.sampled(lens, settings).equals(lens),
                "finite aperture suspends lighting scale");
        for (double invalid : new double[] {0, .49, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                settings.withLightingScale(invalid);
                throw new AssertionError("Invalid scale accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            RenderCapabilities.CPU.validate(
                    ReconstructionCheck.world(0, false, false), output, settings);
            throw new AssertionError("CPU accepted unsupported lighting scale");
        } catch (RenderCapabilities.UnsupportedInput expected) {
        }
        try {
            GpuLighting.validate(output, new RenderView(output.camera(), 192, 160), settings);
            throw new AssertionError("Mismatched lighting grid accepted");
        } catch (IllegalArgumentException expected) {
        }
        require(
                settings.withSeed(9).withBloom(Bloom.SIMPLE).withPaused(true).lightingScale() == .5,
                "settings edits retain lighting scale");
    }

    private static void sky(GpuKernel kernel, Camera camera, RenderSettings settings) {
        var hidden =
                new SceneInstance(
                        "behind camera",
                        SceneInstance.box(),
                        new Transform(new Vec3(0, 0, -100), Vec3.ZERO, new Vec3(1, 1, 1)),
                        new Material("hidden", new Vec3(.5f, .5f, .5f)));
        var world =
                new WorldSnapshot(
                        0,
                        List.of(hidden),
                        List.of(),
                        List.of(),
                        new Sky(new Vec3(.2f, .4f, .8f), new Vec3(.2f, .4f, .8f)));
        var output = new RenderView(camera, 191, 159);
        var input = GpuLighting.sampled(output, settings);
        var shown = new GpuKernel.Snapshot();
        var raw = new GpuKernel.Snapshot();
        kernel.trace(
                world,
                input,
                settings,
                0,
                new GpuKernel.Destination(null, null, raw, 1, shown, output));
        var values = shown.read(kernel.gpu);
        for (int p = 0; p < values.length; p += 3) {
            if (Math.abs(values[p] - .2f) > 1e-6
                    || Math.abs(values[p + 1] - .4f) > 1e-6
                    || Math.abs(values[p + 2] - .8f) > 1e-6)
                throw new AssertionError("Sky reconstruction changed constant environment");
        }
        require(
                true,
                "zero-fallback compact queue and sky reconstruction cover every native pixel");
    }

    private static void largeFallbackQueue(
            GpuKernel kernel, Camera camera, RenderSettings settings) {
        // More than 65535 workgroups: verifies the indirect dispatch's second dimension and
        // pixel-indexed sampling after compaction. Pure red cannot be safely demodulated.
        var surface =
                new SceneInstance(
                        "red",
                        SceneInstance.box(),
                        new Transform(new Vec3(0, 1, 2), Vec3.ZERO, new Vec3(100, 100, .2f)),
                        new Material("red", new Vec3(1, 0, 0)));
        var world =
                new WorldSnapshot(
                        0,
                        List.of(surface),
                        List.of(),
                        List.of(),
                        new Sky(new Vec3(1, 1, 1), new Vec3(1, 1, 1)));
        var output = new RenderView(camera, 2112, 2048);
        var active = settings.withPathDepth(1);
        var input = GpuLighting.sampled(output, active);
        var raw = new GpuKernel.Snapshot();
        var shown = new GpuKernel.Snapshot();
        kernel.trace(
                world,
                input,
                active,
                0,
                new GpuKernel.Destination(null, null, raw, 1, shown, output));
        var values = shown.read(kernel.gpu);
        for (int p = 0; p < values.length; p += 3) {
            if (values[p] != 1 || values[p + 1] != 0 || values[p + 2] != 0)
                throw new AssertionError("Compacted fallback missed native pixel " + p / 3);
        }
        require(
                true,
                "full-frame fallback queue spans multiple indirect dispatch rows without holes");
    }

    private static void visibility(GpuKernel kernel, Camera camera, RenderSettings settings) {
        var objects = new ArrayList<SceneInstance>();
        objects.add(
                new SceneInstance(
                        "blue",
                        SceneInstance.box(),
                        new Transform(new Vec3(0, 1, 3), Vec3.ZERO, new Vec3(8, 8, .2f)),
                        new Material("blue", new Vec3(0, 0, 1))));
        objects.add(
                new SceneInstance(
                        "thin red",
                        SceneInstance.box(),
                        new Transform(new Vec3(.037f, 1, 1), Vec3.ZERO, new Vec3(.012f, 8, .2f)),
                        new Material("red", new Vec3(1, 0, 0))));
        var world =
                new WorldSnapshot(
                        0,
                        objects,
                        List.of(),
                        List.of(),
                        new Sky(new Vec3(1, 1, 1), new Vec3(1, 1, 1)));
        var output = new RenderView(camera, 320, 160);
        var input = GpuLighting.sampled(output, settings);
        var shown = new GpuKernel.Snapshot();
        kernel.trace(
                world,
                input,
                settings,
                0,
                new GpuKernel.Destination(
                        new float[input.width() * input.height() * 3],
                        null,
                        null,
                        1,
                        shown,
                        output));
        var data = shown.read(kernel.gpu);
        var scene = new SceneDocument();
        var query =
                SpatialQuery.prepare(
                        scene.transact(
                                edit -> {
                                    for (var item : objects) {
                                        var node =
                                                edit.createNode(
                                                        item.name(), null, item.transform());
                                        var geometry =
                                                edit.createGeometry(item.name(), item.geometry());
                                        var material =
                                                edit.createMaterial(item.name(), item.material());
                                        edit.assignGeometry(node, geometry, material);
                                    }
                                }));
        int red = 0, blue = 0;
        for (int y = 0; y < output.height(); y++)
            for (int x = 0; x < output.width(); x++) {
                Vec3 sensor =
                        camera.sensor()
                                .origin()
                                .add(camera.sensor().edge1().scale((x + .5f) / output.width()))
                                .add(camera.sensor().edge2().scale((y + .5f) / output.height()));
                var hit =
                        query.nearest(new RayQuery(camera.eye(), sensor.sub(camera.eye())))
                                .orElseThrow();
                int p = (y * output.width() + x) * 3;
                if (hit.worldPosition().z() < 2) {
                    requireChannel(data[p + 2], "thin native red silhouette retains zero blue");
                    red++;
                } else {
                    requireChannel(data[p], "background retains zero red");
                    blue++;
                }
            }
        require(
                red > 0 && blue > 0,
                "native visibility resolves sub-lighting-pixel target silhouettes");
    }

    private static void requireChannel(float value, String message) {
        if (value != 0) throw new AssertionError(message + ": " + value);
    }

    private static void leases(
            WorldSnapshot world, RenderView view, RenderSettings settings, boolean resident)
            throws Exception {
        var session = new GpuRenderSession(world, view, settings);
        session.displayExposure(1);
        session.devicePresentation(resident);
        var first = await(session);
        var raw = copy(first.rawPixels());
        var shown = copy(first.presentationPixels());
        require(
                first.width() == 96 && first.presentationPixels().width() == 191,
                "raw/presentation dimensions remain distinct resident=" + resident);
        int[] display = new int[191 * 159], expected = new int[191 * 159];
        first.presentationPixels().copyDisplayTo(display, .7f);
        DisplayCopy.interleaved(shown, expected, 191, 159, .7f);
        require(Arrays.equals(display, expected), "native presentation supports exposure edits");
        var detached = first.takeDisplayPixels(1);
        var device = first.copyDevicePixels();
        if (resident) {
            device.copyDisplayTo(display, .7f);
            require(
                    Arrays.equals(display, expected),
                    "resident copy retains native dimensions and pixels");
        }
        session.update(world, view, settings.withLightingScale(2. / 3));
        require(
                Arrays.equals(raw, copy(first.rawPixels())),
                "scale edit preserves retained raw lease");
        require(
                !first.reconstructed() && first.takeDisplayPixels(1) == null,
                "scale edit cannot expose stale native packed pixels");
        require(
                first.displayWidth() == 191
                        && first.displayHeight() == 159
                        && (detached == null
                                || detached.length == first.displayWidth() * first.displayHeight()),
                "detached packed dimensions survive presentation invalidation");
        try (var next = await(session)) {
            require(
                    next.width() == 127 && next.presentationPixels().width() == 191,
                    "two-thirds lighting keeps fixed native output");
        }
        session.update(world, view, settings.withTemporal(false, 2));
        try (var next = await(session)) {
            require(
                    next.width() == 191 && !next.reconstructed(),
                    "temporal off restores full native raw rendering");
        }
        session.update(world, view, settings);
        session.update(world, view, settings.withInteractive(true, 8.333, 64, 64));
        try (var next = await(session)) {
            require(
                    next.width() == next.presentationPixels().width(),
                    "adaptive budget suspends separate lighting scale");
        }
        session.update(world, view, settings);
        var last = await(session);
        var retained = copy(last.presentationPixels());
        session.close();
        require(session.awaitClosed(30), "split session retires");
        require(
                Arrays.equals(retained, copy(last.presentationPixels())),
                "native reconstructed lease survives shutdown");
        first.close();
        last.close();
        if (device != null) device.close();
    }
}
