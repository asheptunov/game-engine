package gpu;

import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Flatten 64-lane workgroups across two axes within Vulkan's portable dispatch limits. */
final class GpuDispatch {
    private GpuDispatch() {}

    static int columns(int count) {
        return Math.min(65535, (count + 63) / 64);
    }

    static int rows(int count) {
        int groups = (count + 63) / 64;
        return (groups + columns(count) - 1) / columns(count);
    }

    static void linear(VkCommandBuffer command, int count) {
        vkCmdDispatch(command, columns(count), rows(count), 1);
    }
}
