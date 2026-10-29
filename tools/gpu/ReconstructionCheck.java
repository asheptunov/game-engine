package gpu;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.util.*;

/** Real-device reconstruction, motion, fallback, raw-estimator and lease checks; no window. */
public final class ReconstructionCheck {
    private static final int W = 96, H = 80;
    private static final Camera CAMERA =
            new Camera(
                    new Vec3(0, 1, -5),
                    new Rect(new Vec3(-1, 0, -3), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
    private static final RenderSettings SETTINGS =
            RenderSettings.defaults()
                    .withPathDepth(3)
                    .withSamplesPerBatch(1)
                    .withSampleTarget(1)
                    .withTemporal(true, 1);

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    static WorldSnapshot world(float x, boolean reverse, boolean red) {
        var objects = new ArrayList<SceneInstance>();
        objects.add(
                new SceneInstance(
                        "floor",
                        SceneInstance.box(),
                        new Transform(new Vec3(0, -1, 3), Vec3.ZERO, new Vec3(8, .3f, 8)),
                        new Material("floor", new Vec3(.5f, .6f, .7f))));
        objects.add(
                new SceneInstance(
                        "moving",
                        SceneInstance.box(),
                        new Transform(
                                new Vec3(x, .3f, 2), new Vec3(0, x * 20, 0), new Vec3(.7f, 1, .6f)),
                        new Material(
                                "box", red ? new Vec3(.8f, .1f, .1f) : new Vec3(.2f, .6f, .8f))));
        if (reverse) Collections.reverse(objects);
        return new WorldSnapshot(
                0,
                objects,
                List.of(),
                List.of(),
                new Sky(new Vec3(.08f, .1f, .15f), new Vec3(.7f, .8f, 1)));
    }

    public static void main(String[] args) throws Exception {
        try (var kernel = new GpuKernel();
                var rawKernel = new GpuKernel()) {
            var output = new GpuKernel.Snapshot();
            float[] raw = new float[W * H * 3], reference = new float[raw.length];
            var view = new RenderView(CAMERA, W, H);
            boolean reused = false;
            WorldSnapshot previous = null;
            for (int frame = 0; frame < 12; frame++) {
                var world = world(frame * .04f, frame % 2 == 0, false);
                var moved = ReconstructionBenchmark.moved(CAMERA, W, H, frame);
                kernel.trace(
                        world,
                        moved,
                        SETTINGS,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, output));
                if (frame > 2) verifyMotion(kernel, previous, world);
                previous = world;
                rawKernel.trace(world, moved, SETTINGS.withTemporal(false, 2), 0, reference);
                require(
                        Arrays.equals(raw, reference),
                        "raw equality with camera/object motion frame " + frame);
                float[] shown = output.read(kernel.gpu);
                for (float value : shown) requireFinite(value);
                var fresh = new GpuKernel.Snapshot();
                rawKernel.trace(
                        world,
                        moved,
                        SETTINGS.withTemporal(true, frame + 10),
                        0,
                        new GpuKernel.Destination(reference, null, null, 1, fresh));
                reused |= !Arrays.equals(shown, fresh.read(rawKernel.gpu));
                fresh.source.close();
            }
            require(
                    reused,
                    "history changes presentation across camera/object motion and reordered"
                            + " instances");
            var world = world(.44f, false, false);
            kernel.temporal.reset();
            for (int sample = 0; sample < 8; sample++) {
                kernel.trace(
                        world,
                        view,
                        SETTINGS,
                        sample,
                        new GpuKernel.Destination(raw, null, null, 1, output));
                rawKernel.trace(world, view, SETTINGS.withTemporal(false, 2), sample, reference);
                require(
                        Arrays.equals(raw, reference),
                        "raw progressive mean exact at " + (sample + 1) + " spp");
            }
            for (var selected :
                    List.of(
                            SETTINGS.withFoveated(true),
                            SETTINGS.withFogDownsample(2),
                            SETTINGS.withPathDepth(0))) {
                kernel.trace(
                        world,
                        view,
                        selected,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, output));
                require(
                        !kernel.temporal.active && Arrays.equals(raw, output.read(kernel.gpu)),
                        "unsupported settings restore exact raw output: " + kernel.temporal.label);
            }
            var lens = new RenderView(CAMERA.withAperture(.05f), W, H);
            kernel.trace(
                    world,
                    lens,
                    SETTINGS,
                    0,
                    new GpuKernel.Destination(raw, null, null, 1, output));
            require(
                    !kernel.temporal.active && Arrays.equals(raw, output.read(kernel.gpu)),
                    "finite aperture restores exact raw output");
            // Material edits and lighting edits must seed current appearance, never old color.
            var changed = world(.44f, false, true);
            kernel.trace(
                    changed,
                    view,
                    SETTINGS,
                    0,
                    new GpuKernel.Destination(raw, null, null, 1, output));
            var fresh = new GpuKernel.Snapshot();
            rawKernel.temporal.reset();
            rawKernel.trace(
                    changed,
                    view,
                    SETTINGS,
                    0,
                    new GpuKernel.Destination(reference, null, null, 1, fresh));
            require(
                    Arrays.equals(output.read(kernel.gpu), fresh.read(rawKernel.gpu)),
                    "history reset seeds current material after fallback");
            var dark = new WorldSnapshot(1, changed.instances(), List.of(), List.of(), Sky.BLACK);
            kernel.trace(
                    dark, view, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, output));
            require(
                    Arrays.stream(toDouble(output.read(kernel.gpu))).allMatch(v -> v == 0),
                    "lighting change clears bright history");
            disocclusion(kernel, rawKernel, view, output, fresh, raw, reference);
            var blackObjects = new ArrayList<>(world.instances());
            blackObjects.set(
                    1,
                    blackObjects
                            .get(1)
                            .withMaterial(new Material("zero red", new Vec3(0, .6f, .8f))));
            var black = new WorldSnapshot(0, blackObjects, List.of(), List.of(), world.sky());
            kernel.trace(
                    black,
                    view,
                    SETTINGS,
                    0,
                    new GpuKernel.Destination(raw, null, null, 1, output));
            float[] blackShown = output.read(kernel.gpu);
            int zeroCount = 0;
            var blackGuides = copyGuides(kernel);
            for (int p = 0; p < W * H; p++)
                if (blackGuides.getFloat(p * 96 + 28) > 0
                        && blackGuides.getFloat(p * 96 + 48) == 0) {
                    zeroCount++;
                    if (raw[p * 3] != 0 || blackShown[p * 3] != 0)
                        throw new AssertionError("Black material channel gained light");
                }
            require(zeroCount > 50, "demodulation preserves black material channels");
            var glass =
                    new SceneInstance(
                            "glass",
                            SceneInstance.box(),
                            new Transform(new Vec3(0, 1, 3), Vec3.ZERO, new Vec3(1, 1, 1)),
                            new Material(
                                    "glass",
                                    new Vec3(1, 1, 1),
                                    Material.Kind.DIELECTRIC,
                                    1.5f,
                                    Vec3.ZERO));
            var glassWorld =
                    new WorldSnapshot(0, List.of(glass), List.of(), List.of(), world.sky());
            kernel.trace(
                    glassWorld,
                    view,
                    SETTINGS.withPathDepth(8),
                    0,
                    new GpuKernel.Destination(raw, null, null, 1, output));
            require(
                    Arrays.equals(raw, output.read(kernel.gpu)),
                    "glass and sky retain exact raw pixels");
            var small = new RenderView(CAMERA, 64, 64);
            kernel.trace(
                    world,
                    small,
                    SETTINGS,
                    0,
                    new GpuKernel.Destination(new float[64 * 64 * 3], null, null, 1, output));
            require(
                    output.read(kernel.gpu).length == 64 * 64 * 3,
                    "grid resize replaces histories and publication dimensions");
        }
        leases(false);
        leases(true);
        System.out.println("GPU reconstruction checks passed.");
    }

    private static double[] toDouble(float[] data) {
        double[] r = new double[data.length];
        for (int i = 0; i < r.length; i++) r[i] = data[i];
        return r;
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value) || value < 0)
            throw new AssertionError("Invalid reconstructed radiance " + value);
    }

    private static RenderImage await(GpuRenderSession session) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
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

    private static float[] copy(RgbPixels pixels) {
        float[] data = new float[pixels.width() * pixels.height() * 3];
        for (int y = 0; y < pixels.height(); y++)
            for (int x = 0; x < pixels.width(); x++)
                for (int c = 0; c < 3; c++)
                    data[(y * pixels.width() + x) * 3 + c] = pixels.value(c, x, y);
        return data;
    }

    private static void leases(boolean resident) throws Exception {
        var view = new RenderView(CAMERA, W, H);
        var world = world(0, false, false);
        var session = new GpuRenderSession(world, view, SETTINGS);
        session.displayExposure(1);
        session.devicePresentation(resident);
        var first = await(session);
        require(first.reconstructed(), "session exposes reconstructed image resident=" + resident);
        var raw = copy(first.rawPixels());
        var shown = copy(first.presentationPixels());
        require(!Arrays.equals(raw, shown), "raw and presentation are separate");
        int[] display = new int[W * H], expected = new int[W * H];
        first.presentationPixels().copyDisplayTo(display, .7f);
        DisplayCopy.interleaved(shown, expected, W, H, .7f);
        require(
                Arrays.equals(display, expected),
                "exposure conversion reads reconstructed snapshot");
        var device = first.copyDevicePixels();
        if (resident) require(device != null, "resident presentation copy available");
        if (resident) {
            device.copyDisplayTo(display, .7f);
            require(
                    Arrays.equals(display, expected),
                    "resident copy contains reconstructed rather than raw pixels");
        }
        session.update(world(.15f, false, false), view, SETTINGS);
        try (var next = await(session)) {
            require(
                    Arrays.equals(raw, copy(first.rawPixels()))
                            && Arrays.equals(shown, copy(first.presentationPixels())),
                    "held raw and presentation leases survive new publication");
        }
        session.update(world, view, SETTINGS.withTemporal(false, 2));
        require(
                !first.reconstructed()
                        && Arrays.equals(raw, copy(first.presentationPixels()))
                        && first.takeDisplayPixels(1) == null,
                "off restores raw immediately without stale packed output");
        session.update(world, view, SETTINGS.withTemporal(true, 3));
        require(!first.reconstructed(), "off/on cannot revive old reconstruction revision");
        session.update(world, view, SETTINGS.withTemporal(false, 1));
        session.update(world, view, SETTINGS);
        require(
                !first.reconstructed(),
                "off/on cannot revive an old image even with an unchanged caller revision");
        session.suspend();
        var finalImage = await(session);
        var finalShown = copy(finalImage.presentationPixels());
        var finalRaw = copy(finalImage.rawPixels());
        session.update(world, view, SETTINGS.withTemporal(false, 1));
        session.update(world, view, SETTINGS);
        var toggled = await(session);
        require(
                toggled.reconstructed()
                        && Arrays.equals(finalRaw, copy(toggled.rawPixels()))
                        && Arrays.equals(finalShown, copy(toggled.presentationPixels())),
                "converged same-view off/on with unchanged revision publishes a complete image");
        finalImage.close();
        finalImage = toggled;
        finalShown = copy(finalImage.presentationPixels());
        session.close();
        require(session.awaitClosed(15), "GPU owner closes");
        require(
                Arrays.equals(finalShown, copy(finalImage.presentationPixels())),
                "retained presentation survives session shutdown");
        finalImage.close();
        first.close();
        if (device != null) device.close();
    }

    private static void verifyMotion(GpuKernel kernel, WorldSnapshot before, WorldSnapshot after) {
        var source = kernel.temporal.guide();
        try (var staging =
                        kernel.gpu.buffer(
                                source.size,
                                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                true);
                var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            kernel.gpu.begin();
            kernel.gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
            org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(
                    kernel.gpu.command,
                    source.handle,
                    staging.handle,
                    org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack).size(source.size));
            kernel.gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT);
            kernel.gpu.submit();
            int moving = 0;
            for (int i = 0; i < after.instances().size(); i++) {
                var object = after.instances().get(i);
                var old =
                        before.instances().stream()
                                .filter(v -> v.name().equals(object.name()))
                                .findFirst()
                                .orElseThrow();
                float id = kernel.temporal.motion.mapped.getFloat(128 + i * 64 + 48);
                for (int p = 0; p < W * H; p++) {
                    int offset = p * 96;
                    if (staging.mapped.getFloat(offset + 12) != id
                            || staging.mapped.getFloat(offset + 44) == 0) continue;
                    Vec3 point = vector(staging.mapped, offset),
                            previousPoint = vector(staging.mapped, offset + 32);
                    Vec3 expected = old.transform().point(object.transform().inversePoint(point));
                    if (expected.sub(previousPoint).length() > .0001)
                        throw new AssertionError("Object motion mismatch " + object.name());
                    if (object.name().equals("moving")) moving++;
                }
            }
            require(
                    moving > 50,
                    "previous surface positions follow translated/rotated object despite instance"
                            + " reorder");
        }
    }

    private static java.nio.ByteBuffer copyGuides(GpuKernel kernel) {
        var source = kernel.temporal.guide();
        try (var staging =
                        kernel.gpu.buffer(
                                source.size,
                                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                true);
                var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            kernel.gpu.begin();
            kernel.gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
            org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(
                    kernel.gpu.command,
                    source.handle,
                    staging.handle,
                    org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack).size(source.size));
            kernel.gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT);
            kernel.gpu.submit();
            var data =
                    java.nio.ByteBuffer.allocate(Math.toIntExact(source.size))
                            .order(java.nio.ByteOrder.nativeOrder());
            data.put(staging.mapped.duplicate());
            data.flip();
            return data;
        }
    }

    private static void disocclusion(
            GpuKernel kernel,
            GpuKernel referenceKernel,
            RenderView view,
            GpuKernel.Snapshot output,
            GpuKernel.Snapshot fresh,
            float[] raw,
            float[] reference) {
        kernel.temporal.reset();
        kernel.trace(
                world(0, false, false),
                view,
                SETTINGS,
                0,
                new GpuKernel.Destination(raw, null, null, 1, output));
        var exposed = world(3.5f, false, false);
        kernel.trace(
                exposed, view, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, output));
        referenceKernel.temporal.reset();
        referenceKernel.trace(
                exposed,
                view,
                SETTINGS,
                0,
                new GpuKernel.Destination(reference, null, null, 1, fresh));
        float[] actual = output.read(kernel.gpu), expected = fresh.read(referenceKernel.gpu);
        for (int y = 29; y < 35; y++)
            for (int x = 43; x < 51; x++)
                for (int c = 0; c < 3; c++) {
                    int i = (y * W + x) * 3 + c;
                    if (actual[i] != expected[i])
                        throw new AssertionError(
                                "Disoccluded floor retained object history at " + x + "," + y);
                }
        require(true, "newly exposed floor rejects the occluding object's history");
    }

    private static Vec3 vector(java.nio.ByteBuffer data, int offset) {
        return new Vec3(
                data.getFloat(offset), data.getFloat(offset + 4), data.getFloat(offset + 8));
    }
}
