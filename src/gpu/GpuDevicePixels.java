package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

import engine.DevicePixels;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * Retained immutable storage; a publication replaces its storage before writing over this frame.
 */
final class GpuDevicePixels implements DevicePixels {
    final GpuRenderSession session;
    final VulkanDevice gpu;
    final GpuKernel.Snapshot snapshot;
    private final int width;
    private final int height;
    private final java.util.concurrent.atomic.AtomicInteger owners =
            new java.util.concurrent.atomic.AtomicInteger(1);
    volatile float[] retiredPixels;

    GpuDevicePixels(
            GpuRenderSession session,
            VulkanDevice gpu,
            GpuKernel.Snapshot source,
            int width,
            int height,
            boolean copy) {
        this.session = session;
        this.gpu = gpu;
        this.width = width;
        this.height = height;
        snapshot = copy ? session.takeDisplayStorage() : source;
        if (copy) {
            copyStorage(source, width, height);
        }
        snapshot.displayReaders++;
    }

    private void copyStorage(GpuKernel.Snapshot source, int width, int height) {
        snapshot.prepare(gpu, width * height);
        try (var stack = MemoryStack.stackPush()) {
            gpu.begin();
            gpu.barrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdCopyBuffer(
                    gpu.command,
                    source.source.handle,
                    snapshot.source.handle,
                    VkBufferCopy.calloc(1, stack).size(source.source.size));
            gpu.submit();
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public DevicePixels retain() {
        for (; ; ) {
            int count = owners.get();
            if (count <= 0) {
                throw new IllegalStateException("Released device frame");
            }
            if (owners.compareAndSet(count, count + 1)) {
                return this;
            }
        }
    }

    public void copyDisplayTo(int[] destination, float exposure) {
        copyDisplayTo(destination, exposure, engine.Bloom.OFF);
    }

    public void copyDisplayTo(int[] destination, float exposure, engine.Bloom mode) {
        if (owners.get() <= 0) {
            throw new IllegalStateException("Released device frame");
        }
        float[] raw = retiredPixels;
        if (raw == null) {
            raw = session.readDeviceFrame(this);
        }
        new engine.Bloom.Filter().copy(raw, width, height, destination, exposure, mode);
    }

    public void close() {
        int remaining = owners.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("Device frame released twice");
        }
        if (remaining == 0) {
            session.releaseDeviceFrame(this);
        }
    }

    void retireOnOwner() {
        if (owners.get() > 0) {
            retiredPixels = snapshot.read(gpu);
        }
        // VulkanDevice retires every buffer after retained CPU images have been materialized.
    }
}
