package gpu;

import static org.lwjgl.vulkan.VK10.*;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.*;

/** Real-device NRD signal contracts, history, raw means and lease lifetime; no visible window. */
public final class NrdCheck {
    private static final int W = 96, H = 80;
    private static final Camera CAMERA =
            new Camera(
                    new Vec3(0, 1, -5),
                    new Rect(new Vec3(-1, 0, -3), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
    private static final RenderView VIEW = new RenderView(CAMERA, W, H);
    private static final RenderSettings SETTINGS =
            RenderSettings.defaults()
                    .withPathDepth(3)
                    .withTemporal(true, 1)
                    .withDenoiser(Denoiser.NRD_RELAX);

    public static void main(String[] args) throws Exception {
        cameraMatrices();
        try (var kernel = new GpuKernel()) {
            signalsAndMeans(kernel);
            resetsAndFallbacks(kernel);
        }
        leases(false);
        leases(true);
        require(
                RenderCapabilities.CPU
                        .issues(ReconstructionCheck.world(0, false, false), VIEW, SETTINGS)
                        .stream()
                        .anyMatch(issue -> issue.path().equals("settings.denoiser")),
                "CPU rejects NRD settings");
        System.out.println("NRD checks passed.");
    }

    private static void cameraMatrices() {
        for (var camera :
                List.of(CAMERA, ReconstructionBenchmark.moved(CAMERA, W, H, 9).camera())) {
            float[] matrices = GpuNrdCamera.matrices(camera, camera);
            for (int y = 0; y <= 1; y++)
                for (int x = 0; x <= 1; x++) {
                    var point =
                            camera.sensor()
                                    .origin()
                                    .add(camera.sensor().edge1().scale(x))
                                    .add(camera.sensor().edge2().scale(y));
                    float[] view =
                            multiply(
                                    matrices, 32, new float[] {point.x(), point.y(), point.z(), 1});
                    float[] clip = multiply(matrices, 0, view);
                    require(
                            Math.abs(clip[0] / clip[3] - (2 * x - 1)) < 1e-5
                                    && Math.abs(clip[1] / clip[3] - (2 * y - 1)) < 1e-5
                                    && view[2] > 0,
                            "column-major camera projection corner " + x + "," + y);
                }
        }
    }

    private static float[] multiply(float[] matrix, int offset, float[] vector) {
        float[] result = new float[4];
        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++)
                result[row] += matrix[offset + col * 4 + row] * vector[col];
        return result;
    }

    private static void signalsAndMeans(GpuKernel kernel) {
        var world = ReconstructionCheck.world(0, false, false);
        var shown = new GpuKernel.Snapshot();
        float[] raw = new float[W * H * 3];
        double[] means = new double[raw.length];
        float[] firstFresh = null;
        float[] firstRaw = null;
        for (int sample = 0; sample < 6; sample++) {
            kernel.trace(
                    world,
                    VIEW,
                    SETTINGS,
                    sample,
                    new GpuKernel.Destination(raw, null, null, 1, shown));
            var fresh = readBuffer(kernel.gpu, kernel.temporal.nrd.fresh);
            if (sample == 0) {
                firstFresh = fresh;
                firstRaw = raw.clone();
            }
            if (sample == 1)
                require(
                        !Arrays.equals(firstFresh, fresh),
                        "fresh NRD samples change during stationary refinement");
            for (int pixel = 0; pixel < W * H; pixel++)
                for (int c = 0; c < 3; c++) {
                    int index = pixel * 3 + c;
                    means[index] += (fresh[pixel * 4 + c] - means[index]) / (sample + 1);
                    if (Math.abs(raw[index] - means[index]) > 1e-6)
                        throw new AssertionError("NRD contaminated raw progressive mean");
                }
            finite(shown.read(kernel.gpu));
        }
        require(true, "raw means equal independently accumulated fresh paths at six samples");
        float[] staticMotion = readImage(kernel.gpu, kernel.temporal.nrd.pass.signals[0], 4);
        for (float value : staticMotion)
            if (Math.abs(value) > 1e-5) throw new AssertionError("Static motion was not zero");
        kernel.trace(
                ReconstructionCheck.world(.1f, false, false),
                VIEW,
                SETTINGS,
                0,
                new GpuKernel.Destination(raw, null, null, 1, shown));
        float[] guides = readBuffer(kernel.gpu, kernel.temporal.guide());
        float[] motion = readImage(kernel.gpu, kernel.temporal.nrd.pass.signals[0], 4);
        float[] depth = readImage(kernel.gpu, kernel.temporal.nrd.pass.signals[2], 1);
        int moved = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int g = (y * W + x) * 24, texel = (H - 1 - y) * W + x;
                if (guides[g + 7] == 0) continue;
                float expectedDepth = guides[g + 2] - CAMERA.eye().z();
                if (Math.abs(depth[texel] - expectedDepth) > 1e-4)
                    throw new AssertionError("View depth/orientation mismatch");
                for (int c = 0; c < 3; c++) {
                    float expected = guides[g + 8 + c] - guides[g + c];
                    if (Math.abs(motion[texel * 4 + c] - expected) > 1e-5)
                        throw new AssertionError("World motion mismatch");
                }
                if (Math.abs(motion[texel * 4]) > .01) moved++;
            }
        require(
                moved > 20,
                "object translation/rotation produces previous-minus-current world motion with"
                    + " top-down textures");
        kernel.trace(
                world, VIEW, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
        require(
                Arrays.equals(firstRaw, raw),
                "captured raw image is independent of NRD visit/history order");
        shown.source.close();
    }

    private static void resetsAndFallbacks(GpuKernel kernel) {
        var world = ReconstructionCheck.world(0, false, false);
        var shown = new GpuKernel.Snapshot();
        float[] raw = new float[W * H * 3];
        kernel.temporal.reset();
        kernel.trace(
                world, VIEW, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
        float[] first = shown.read(kernel.gpu).clone();
        kernel.trace(
                world,
                VIEW,
                SETTINGS.withRestartRevision(1),
                0,
                new GpuKernel.Destination(raw, null, null, 1, shown));
        require(
                Arrays.equals(first, shown.read(kernel.gpu)),
                "restart clears NRD history and reproduces first image");
        for (var settings :
                List.of(
                        SETTINGS.withTemporal(false, 2),
                        SETTINGS.withFoveated(true),
                        SETTINGS.withFogDownsample(2),
                        SETTINGS.withPathDepth(0))) {
            kernel.trace(
                    world, VIEW, settings, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
            require(
                    Arrays.equals(raw, shown.read(kernel.gpu)) && kernel.temporal.nrd == null,
                    "unsupported combination restores raw and releases NRD");
        }
        kernel.trace(
                world,
                new RenderView(CAMERA.withAperture(.05f), W, H),
                SETTINGS,
                0,
                new GpuKernel.Destination(raw, null, null, 1, shown));
        require(Arrays.equals(raw, shown.read(kernel.gpu)), "finite aperture restores raw");
        var distant =
                new SceneInstance(
                        "offscreen",
                        SceneInstance.box(),
                        new Transform(new Vec3(1000, 1000, 1000), Vec3.ZERO, new Vec3(1, 1, 1)),
                        new Material("offscreen", new Vec3(1, 1, 1)));
        var sky =
                new WorldSnapshot(
                        0,
                        List.of(distant),
                        List.of(),
                        List.of(),
                        new Sky(new Vec3(.2f, .3f, .4f), new Vec3(.6f, .7f, .8f)));
        kernel.trace(sky, VIEW, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
        require(
                Arrays.equals(raw, shown.read(kernel.gpu)),
                "sky is excluded from diffuse NRD and remains exact raw");
        var farObject =
                new SceneInstance(
                        "beyond-denoising-range",
                        SceneInstance.box(),
                        new Transform(
                                new Vec3(0, 0, 200000), Vec3.ZERO, new Vec3(1000000, 1000000, 1)),
                        new Material("far", new Vec3(.5f, .6f, .7f)));
        var farWorld = new WorldSnapshot(0, List.of(farObject), List.of(), List.of(), sky.sky());
        kernel.trace(
                farWorld, VIEW, SETTINGS, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
        require(
                Arrays.equals(raw, shown.read(kernel.gpu)),
                "surfaces beyond NRD range use raw instead of stale denoiser output");
        var large = new RenderView(CAMERA, 191, 159);
        var split = SETTINGS.withLightingScale(.5);
        kernel.trace(
                world,
                GpuLighting.sampled(large, split),
                split,
                0,
                new GpuKernel.Destination(null, null, new GpuKernel.Snapshot(), 1, shown, large));
        require(
                shown.read(kernel.gpu).length == 191 * 159 * 3,
                "NRD composes odd native visibility dimensions");
        finite(shown.read(kernel.gpu));
        require(kernel.temporal.nrd.frameIndex == 1, "resize restarts NRD history");
        shown.source.close();
    }

    static float[] readBuffer(VulkanDevice gpu, VulkanDevice.Buffer source) {
        try (var target = gpu.buffer(source.size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
                var stack = MemoryStack.stackPush()) {
            gpu.begin();
            readBarrier(gpu);
            vkCmdCopyBuffer(
                    gpu.command,
                    source.handle,
                    target.handle,
                    VkBufferCopy.calloc(1, stack).size(source.size));
            gpu.submit();
            return floats(target.mapped, Math.toIntExact(source.size / 4));
        }
    }

    private static float[] readImage(VulkanDevice gpu, GpuImage source, int channels) {
        try (var target =
                        gpu.buffer(
                                (long) source.width * source.height * channels * 4,
                                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                true);
                var stack = MemoryStack.stackPush()) {
            gpu.begin();
            readBarrier(gpu);
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.imageExtent().set(source.width, source.height, 1);
            vkCmdCopyImageToBuffer(
                    gpu.command, source.image, VK_IMAGE_LAYOUT_GENERAL, target.handle, copy);
            gpu.submit();
            return floats(target.mapped, source.width * source.height * channels);
        }
    }

    private static void readBarrier(VulkanDevice gpu) {
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT);
    }

    private static float[] floats(java.nio.ByteBuffer data, int length) {
        float[] values = new float[length];
        data.asFloatBuffer().get(values);
        return values;
    }

    private static void finite(float[] values) {
        for (float value : values)
            if (!Float.isFinite(value) || value < 0)
                throw new AssertionError("Invalid radiance: " + value);
    }

    private static void leases(boolean resident) throws Exception { leases(resident, Upscaler.OFF); }

    static void leases(boolean resident, Upscaler upscaler) throws Exception {
        var world = ReconstructionCheck.world(0, false, false);
        var settings = SETTINGS.withLightingScale(upscaler == Upscaler.OFF ? .5 : 1).withUpscaler(upscaler);
        var view = new RenderView(CAMERA, 192, 160);
        var session = new GpuRenderSession(world, view, settings);
        session.displayExposure(1);
        session.devicePresentation(resident);
        var first = await(session);
        float[] shown = copy(first.presentationPixels());
        int[] expected = new int[192 * 160];
        DisplayCopy.interleaved(shown, expected, 192, 160, 1);
        var device = first.copyDevicePixels();
        if (resident) {
            int[] packed = new int[expected.length];
            device.copyDisplayTo(packed, 1);
            require(
                    Arrays.equals(expected, packed),
                    "resident NRD pixels match CPU display conversion");
        } else
            require(
                    Arrays.equals(expected, first.takeDisplayPixels(1)),
                    "packed NRD pixels match native display dimensions");
        session.update(ReconstructionCheck.world(.1f, false, false), view, settings);
        try (var next = await(session)) {
            require(
                    Arrays.equals(shown, copy(first.presentationPixels())),
                    "held NRD image survives moving publication");
        }
        session.update(world, view, upscaler == Upscaler.OFF ? settings.withDenoiser(Denoiser.BUILT_IN) : settings.withUpscaler(Upscaler.OFF));
        require(
                !first.reconstructed(),
                "switching denoiser invalidates old presentation immediately");
        session.update(world, view, settings);
        require(!first.reconstructed(), "rapid denoiser off/on cannot revive old NRD presentation");
        session.close();
        if (device != null) {
            int[] packed = new int[expected.length];
            device.copyDisplayTo(packed, 1);
            require(Arrays.equals(expected, packed), "resident NRD lease survives session close");
            device.close();
        }
        first.close();
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

    private static float[] copy(RgbPixels pixels) {
        float[] result = new float[pixels.width() * pixels.height() * 3];
        for (int y = 0; y < pixels.height(); y++)
            for (int x = 0; x < pixels.width(); x++)
                for (int c = 0; c < 3; c++)
                    result[(y * pixels.width() + x) * 3 + c] = pixels.value(c, x, y);
        return result;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
}
