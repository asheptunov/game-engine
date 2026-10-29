package gpu;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

import engine.RenderView;

/** Fresh-sample RELAX history, independent of raw progressive means and the built-in filter. */
final class GpuNrd implements AutoCloseable {
    private final VulkanDevice gpu;
    GpuNrdPass pass;
    private GpuNrdConversion conversion;
    VulkanDevice.Buffer fresh;
    private RenderView view;
    private int width;
    private int height;
    private long lastFrame;
    int frameIndex;

    GpuNrd(VulkanDevice gpu) {
        this.gpu = gpu;
        fresh =
                gpu.buffer(
                        16,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        false);
    }

    void prepare(RenderView next, RenderView previous, boolean valid) {
        boolean resize = width != next.width() || height != next.height();
        if (resize) {
            if (pass != null) {
                pass.close();
            }
            pass = null;
            fresh.close();
            width = next.width();
            height = next.height();
            fresh =
                    gpu.buffer(
                            (long) width * height * 16,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            false);
            pass = new GpuNrdPass(gpu, width, height);
        }
        if (conversion == null) {
            conversion = new GpuNrdConversion(gpu);
        }
        long now = System.nanoTime();
        float millis = lastFrame == 0 ? 16.67f : Math.max(.1f, (now - lastFrame) / 1e6f);
        boolean reset = resize || !valid || previous == null || millis > 500;
        if (previous != null) {
            reset |=
                    GpuNrdCamera.forward(next.camera()).dot(GpuNrdCamera.forward(previous.camera()))
                            < .5f;
            reset |= next.camera().eye().sub(previous.camera().eye()).length() > 5;
        }
        if (reset) {
            frameIndex = 0;
        }
        pass.prepare(
                GpuNrdCamera.matrices(next.camera(), reset ? next.camera() : previous.camera()),
                width,
                height,
                frameIndex,
                reset,
                millis);
        view = next;
        lastFrame = now;
    }

    void record(
            VulkanDevice.Buffer raw,
            VulkanDevice.Buffer guides,
            VulkanDevice.Buffer albedo,
            VulkanDevice.Buffer presented) {
        pass.initializeImages();
        conversion.bind(
                new VulkanDevice.Buffer[] {raw, guides, albedo, fresh, presented}, pass.signals);
        conversion.record(width, height, view.camera(), false);
        pass.record();
        conversion.record(width, height, view.camera(), true);
        frameIndex++;
    }

    public void close() {
        if (pass != null) {
            pass.close();
            pass = null;
        }
        if (conversion != null) {
            conversion.close();
            conversion = null;
        }
        fresh.close();
    }
}
