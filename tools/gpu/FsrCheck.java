package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/** Real-device upscaling, raw estimator, fallback, cut and resize checks without a window. */
public final class FsrCheck {
    public static void main(String[] args) throws Exception {
        NrdCheck.leases(false, Upscaler.FSR_PERFORMANCE);
        NrdCheck.leases(true, Upscaler.FSR_QUALITY);
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(8)
                        .withTemporal(true, 1)
                        .withDenoiser(Denoiser.NRD_RELAX)
                        .withUpscaler(Upscaler.FSR_PERFORMANCE);
        var frames = LightingQualityScene.frames();
        Files.createDirectories(Path.of("out/fsr/check"));
        try (var kernel = new GpuKernel()) {
            var raw = new GpuKernel.Snapshot();
            var shown = new GpuKernel.Snapshot();
            double[] mean = new double[256 * 144 * 3];
            long plateau = 0;
            for (int i = 0; i < 48; i++) {
                var state = frames.get(i < 32 ? 0 : (i - 32) % frames.size());
                var input = GpuLighting.sampled(state.view(), settings);
                kernel.trace(
                        state.world(),
                        input,
                        settings,
                        i < 32 ? i : 0,
                        new GpuKernel.Destination(null, null, raw, 1, shown, state.view()));
                var pixels = shown.read(kernel.gpu);
                require(pixels.length == 512 * 288 * 3, "FSR output dimensions");
                require(
                        kernel.fsr.active && !kernel.lighting.active,
                        "FSR skips native visibility");
                require(raw.read(kernel.gpu).length == 256 * 144 * 3, "Raw sampled grid retained");
                if (i < 32) {
                    var fresh = NrdCheck.readBuffer(kernel.gpu, kernel.temporal.nrd.fresh);
                    var actual = raw.read(kernel.gpu);
                    for (int p = 0; p < actual.length; p++) {
                        mean[p] += (fresh[(p / 3) * 4 + p % 3] - mean[p]) / (i + 1);
                        require(
                                Math.abs(mean[p] - actual[p]) < 1e-5,
                                "Raw means stay independent of FSR");
                    }
                    if (i == 2) plateau = kernel.gpu.allocatedBytes;
                    if (i > 2)
                        require(kernel.gpu.allocatedBytes == plateau, "Bounded stationary storage");
                    if (i == 4) stationaryMotion(kernel);
                }
                double total = 0;
                for (float v : pixels) {
                    require(Float.isFinite(v) && v >= 0, "Finite output");
                    total += v;
                }
                require(total > 10, "Nonblack output");
                if (i == 0 || i == 31 || i == 39 || i == 43)
                    ReconstructionBenchmark.save(
                            Path.of("out/fsr/check/frame-" + i + ".png"), pixels, 512, 288);
            }
            var frame = frames.get(0);
            var cut = ReconstructionBenchmark.moved(frame.view().camera(), 512, 288, 1000);
            kernel.trace(
                    frame.world(),
                    GpuLighting.sampled(cut, settings),
                    settings,
                    0,
                    new GpuKernel.Destination(null, null, raw, 1, shown, cut));
            require(
                    kernel.temporal.nrd.resetHistory,
                    "Camera cut resets both denoising and upscaling history");
            var resized = new RenderView(frame.view().camera(), 320, 192);
            kernel.trace(
                    frame.world(),
                    GpuLighting.sampled(resized, settings),
                    settings,
                    0,
                    new GpuKernel.Destination(null, null, raw, 1, shown, resized));
            require(shown.read(kernel.gpu).length == 320 * 192 * 3, "Resize output");
            var off = settings.withUpscaler(Upscaler.OFF);
            kernel.trace(
                    frame.world(),
                    frame.view(),
                    off,
                    0,
                    new GpuKernel.Destination(null, null, raw, 1, shown, frame.view()));
            require(
                    !kernel.fsr.active
                            && kernel.temporal.nrd.jitterX == 0
                            && kernel.temporal.nrd.jitterY == 0,
                    "Off releases FSR and restores zero jitter");
            require(
                    GpuFsr.fallback(frame.view(), settings.withInteractive(true, 8.33, 0, 0))
                            != null,
                    "Adaptive mode suspends FSR");
            require(
                    GpuFsr.fallback(frame.view(), settings.withDenoiser(Denoiser.BUILT_IN)) != null,
                    "Unsupported denoiser suspends FSR");
            require(
                    GpuFsr.fallback(frame.view(), settings.withFoveated(true)) != null,
                    "Foveation suspends FSR");
            raw.source.close();
            shown.source.close();
        }
        System.out.println("FSR image, resize and toggle checks passed.");
    }

    private static void stationaryMotion(GpuKernel kernel) {
        var source = kernel.fsr.images[2];
        try (var target =
                        kernel.gpu.buffer(
                                (long) source.width * source.height * 4,
                                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                true);
                var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var gpu = kernel.gpu;
            gpu.begin();
            gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
            var copy = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
            copy.imageSubresource()
                    .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(1);
            copy.imageExtent().set(source.width, source.height, 1);
            org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(
                    gpu.command,
                    source.image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL,
                    target.handle,
                    copy);
            gpu.submit();
            for (int i = 0; i < source.width * source.height * 2; i++) {
                float mv = Float.float16ToFloat(target.mapped.getShort(i * 2));
                require(
                        Float.isFinite(mv) && Math.abs(mv) < .0001,
                        "Stationary motion excludes jitter: " + mv);
            }
        }
    }

    private static void require(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
