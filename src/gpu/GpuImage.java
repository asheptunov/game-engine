package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

/** One 2D compute texture in GENERAL layout; barriers are recorded by the pass scheduler. */
final class GpuImage implements AutoCloseable {
    private final VulkanDevice gpu;
    final int width;
    final int height;
    final int format;
    long image;
    long view;
    private long memory;
    private long bytes;
    private boolean initialized;

    GpuImage(VulkanDevice gpu, int width, int height, int format) {
        this.gpu = gpu;
        this.width = width;
        this.height = height;
        this.format = format;
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(gpu.physical, format, properties);
            int required =
                    VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((properties.optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException("GPU texture format " + format);
            }
            create(stack);
            gpu.resources.add(this);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void create(MemoryStack stack) {
        var handle = stack.longs(0);
        var create =
                VkImageCreateInfo.calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(
                                VK_IMAGE_USAGE_STORAGE_BIT
                                        | VK_IMAGE_USAGE_SAMPLED_BIT
                                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        create.extent().set(width, height, 1);
        VulkanDevice.check(vkCreateImage(gpu.device, create, null, handle), "GPU image");
        image = handle.get(0);
        var requirements = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(gpu.device, image, requirements);
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(gpu.physical, properties);
        int type = -1;
        for (int i = 0; i < properties.memoryTypeCount(); i++) {
            if ((requirements.memoryTypeBits() & (1 << i)) != 0
                    && (properties.memoryTypes(i).propertyFlags()
                                    & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                            != 0) {
                type = i;
                break;
            }
        }
        if (type < 0) {
            throw new IllegalStateException("NRD device memory unavailable");
        }
        VulkanDevice.check(
                vkAllocateMemory(
                        gpu.device,
                        VkMemoryAllocateInfo.calloc(stack)
                                .sType$Default()
                                .allocationSize(requirements.size())
                                .memoryTypeIndex(type),
                        null,
                        handle),
                "GPU image allocation");
        memory = handle.get(0);
        bytes = requirements.size();
        gpu.allocatedBytes += bytes;
        gpu.peakBytes = Math.max(gpu.peakBytes, gpu.allocatedBytes);
        VulkanDevice.check(vkBindImageMemory(gpu.device, image, memory, 0), "GPU image bind");
        var viewCreate =
                VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(format);
        viewCreate
                .subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);
        VulkanDevice.check(
                vkCreateImageView(gpu.device, viewCreate, null, handle), "GPU image view");
        view = handle.get(0);
    }

    void prepare() {
        if (initialized) {
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            var barrier =
                    VkImageMemoryBarrier.calloc(1, stack)
                            .sType$Default()
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .image(image)
                            .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .layerCount(1);
            vkCmdPipelineBarrier(
                    gpu.command,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    barrier);
            var range =
                    VkImageSubresourceRange.calloc(1, stack)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .levelCount(1)
                            .layerCount(1);
            vkCmdClearColorImage(
                    gpu.command,
                    image,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VkClearColorValue.calloc(stack),
                    range);
        }
        initialized = true;
    }

    public void close() {
        if (view != 0) {
            vkDestroyImageView(gpu.device, view, null);
            view = 0;
        }
        if (image != 0) {
            vkDestroyImage(gpu.device, image, null);
            image = 0;
        }
        if (memory != 0) {
            vkFreeMemory(gpu.device, memory, null);
            memory = 0;
            gpu.allocatedBytes -= bytes;
        }
        gpu.resources.remove(this);
    }
}
