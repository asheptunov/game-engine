package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;

import engine.RenderSettings;
import engine.RenderView;
import engine.Upscaler;

import org.lwjgl.system.MemoryStack;

import java.nio.file.Path;

/** Owner-thread FSR history. Render jitter never changes the simulation camera or scene clock. */
final class GpuFsr implements AutoCloseable {
    private final VulkanDevice gpu;
    private GpuFsrLibrary library;
    private GpuImagePass conversion;
    GpuImage[] images;
    private final long[] handles = new long[5];
    private VulkanDevice.Buffer output;
    private RenderView input;
    private RenderView presentation;
    private int frame;
    private long lastFrame;
    boolean active;
    float jitterX;
    float jitterY;

    GpuFsr(VulkanDevice gpu) {
        this.gpu = gpu;
    }

    static String fallback(RenderView view, RenderSettings settings) {
        if (settings.upscaler() == Upscaler.OFF) {
            return "FSR off";
        }
        var temporal = GpuTemporal.fallback(view, settings);
        if (temporal != null) {
            return temporal;
        }
        if (settings.denoiser() != engine.Denoiser.NRD_RELAX) {
            return "FSR requires NRD RELAX";
        }
        if (settings.interactive()) {
            return "FSR requires fixed resolution";
        }
        if (settings.lightingScale() != 1) {
            return "FSR requires full lighting scale";
        }
        return null;
    }

    void prepare(RenderView next, RenderView destination, RenderSettings settings) {
        active = fallback(destination, settings) == null;
        jitterX = jitterY = 0;
        if (!active) {
            close();
            return;
        }
        if (library == null
                || input.width() != next.width()
                || input.height() != next.height()
                || presentation.width() != destination.width()
                || presentation.height() != destination.height()) {
            close();
            active = true;
            try {
                library =
                        new GpuFsrLibrary(
                                gpu,
                                next.width(),
                                next.height(),
                                destination.width(),
                                destination.height());
                gpu.allocatedBytes += library.memory;
                gpu.peakBytes = Math.max(gpu.peakBytes, gpu.allocatedBytes);
                conversion = new GpuImagePass(gpu, Path.of("out/gpu/fsr.spv"), 4, 5, 64);
                images = new GpuImage[5];
                int[] formats = {
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_FORMAT_R32_SFLOAT,
                    VK_FORMAT_R16G16_SFLOAT,
                    VK_FORMAT_R8_UNORM,
                    VK_FORMAT_R16G16B16A16_SFLOAT
                };
                for (int i = 0; i < 5; i++) {
                    var size = i == 4 ? destination : next;
                    images[i] = new GpuImage(gpu, size.width(), size.height(), formats[i]);
                    handles[i] = images[i].image;
                }
                output =
                        gpu.buffer(
                                (long) destination.width() * destination.height() * 16,
                                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                false);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
        input = next;
        presentation = destination;
        library.jitter(frame);
        // FSR reports a projected-image displacement. Ray sample positions move in the opposite
        // direction. NRD takes that sample offset directly; both use top-left pixel coordinates.
        jitterX = -library.jitterX;
        jitterY = -library.jitterY;
    }

    VulkanDevice.Buffer record(VulkanDevice.Buffer color, GpuTemporal temporal) {
        for (var image : images) {
            image.prepare();
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        conversion.bind(
                new VulkanDevice.Buffer[] {color, temporal.guide(), temporal.motion, output},
                images);
        try (var stack = MemoryStack.stackPush()) {
            var push =
                    stack.calloc(64)
                            .putInt(0, input.width())
                            .putInt(4, input.height())
                            .putInt(8, presentation.width())
                            .putInt(12, presentation.height());
            var eye = input.camera().eye();
            var forward = GpuNrdCamera.forward(input.camera());
            push.putFloat(16, eye.x()).putFloat(20, eye.y()).putFloat(24, eye.z());
            push.putFloat(32, forward.x()).putFloat(36, forward.y()).putFloat(40, forward.z());
            push.putFloat(48, jitterX).putFloat(52, -jitterY);
            push.putFloat(56, temporal.lightingConfidence);
            conversion.record(input.width(), input.height(), push);
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            long now = System.nanoTime();
            float millis = lastFrame == 0 ? 16.67f : Math.max(.1f, (now - lastFrame) / 1e6f);
            var sensor = input.camera().sensor();
            float distance = sensor.origin().sub(eye).dot(forward);
            float fov = 2 * (float) Math.atan(sensor.edge2().length() / (2 * distance));
            library.record(
                    gpu.command.address(),
                    handles,
                    millis,
                    fov,
                    temporal.nrd.resetHistory || lastFrame == 0);
            lastFrame = now;
            push.putFloat(28, 1);
            conversion.record(presentation.width(), presentation.height(), push);
        }
        frame = (frame + 1) & 0x7fffffff;
        return output;
    }

    public void close() {
        if (library != null) {
            gpu.allocatedBytes -= library.memory;
            library.close();
            library = null;
        }
        if (images != null) {
            for (var image : images) {
                if (image != null) {
                    image.close();
                }
            }
            images = null;
        }
        if (conversion != null) {
            conversion.close();
            conversion = null;
        }
        if (output != null) {
            output.close();
            output = null;
        }
        active = false;
        frame = 0;
        lastFrame = 0;
    }
}
