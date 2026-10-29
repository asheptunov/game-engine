package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

import engine.RenderSettings;
import engine.RenderView;

/**
 * Owner-thread storage for native visibility and reconstructed lighting. Raw paths stay separate.
 */
final class GpuLighting implements AutoCloseable {
    private final VulkanDevice gpu;
    VulkanDevice.Buffer albedo;
    VulkanDevice.Buffer output;
    VulkanDevice.Buffer fallbackMeans;
    VulkanDevice.Buffer fallbackQueue;
    boolean active;
    int width;
    int height;

    GpuLighting(VulkanDevice gpu) {
        this.gpu = gpu;
        albedo = buffer(16);
        output = buffer(16);
        fallbackMeans = buffer(16);
        fallbackQueue = queue(16);
    }

    static boolean enabled(RenderView view, RenderSettings settings) {
        return settings.lightingScale() < 1
                && !settings.interactive()
                && GpuTemporal.fallback(view, settings) == null;
    }

    static RenderView sampled(RenderView output, RenderSettings settings) {
        if (!enabled(output, settings)) {
            return output;
        }
        return new RenderView(
                output.camera(),
                Math.max(
                        RenderView.MIN_DIMENSION,
                        (int) Math.round(output.width() * settings.lightingScale())),
                Math.max(
                        RenderView.MIN_DIMENSION,
                        (int) Math.round(output.height() * settings.lightingScale())));
    }

    boolean prepare(RenderView input, RenderView presentation, boolean nrd) {
        width = presentation.width();
        height = presentation.height();
        active = input.width() != width || input.height() != height;
        long colorBytes = active || nrd ? (long) input.width() * input.height() * 16 : 16;
        long outputBytes = active ? (long) width * height * 16 : 16;
        boolean changed = albedo.size != colorBytes || output.size != outputBytes;
        if (albedo.size != colorBytes) {
            albedo.close();
            albedo = buffer(colorBytes);
        }
        if (output.size != outputBytes) {
            output.close();
            fallbackMeans.close();
            fallbackQueue.close();
            output = buffer(outputBytes);
            fallbackMeans = buffer(outputBytes);
            fallbackQueue = queue(active ? 16 + (long) width * height * 4 : 16);
        }
        return changed;
    }

    static void validate(RenderView input, RenderView presentation, RenderSettings settings) {
        if (!input.equals(presentation)
                && (!enabled(presentation, settings)
                        || !input.equals(sampled(presentation, settings)))) {
            throw new IllegalArgumentException(
                    "Separate lighting requires matching cameras, fixed resolution, "
                            + "active temporal reconstruction and the configured lighting scale");
        }
    }

    private VulkanDevice.Buffer buffer(long bytes) {
        return gpu.buffer(
                bytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false);
    }

    private VulkanDevice.Buffer queue(long bytes) {
        return gpu.buffer(
                bytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                false);
    }

    void clearFallback(long sample) {
        vkCmdFillBuffer(gpu.command, fallbackQueue.handle, 0, 16, 0);
        if (sample == 0) {
            vkCmdFillBuffer(gpu.command, fallbackMeans.handle, 0, fallbackMeans.size, 0);
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    public void close() {
        albedo.close();
        output.close();
        fallbackMeans.close();
        fallbackQueue.close();
    }
}
