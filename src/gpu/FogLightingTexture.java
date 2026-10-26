package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * Single-owner 3D coefficient atlas: six fields packed 2 by 3, each with isolated texel centers.
 */
final class FogLightingTexture implements AutoCloseable {
    private final VulkanDevice gpu;
    final int resolution;
    long image;
    long view;
    long sampler;
    private long memory;
    private long bytes;
    private boolean initialized;

    FogLightingTexture(VulkanDevice gpu, int resolution) {
        this.gpu = gpu;
        this.resolution = resolution;
        try (var stack = MemoryStack.stackPush()) {
            var format = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(
                    gpu.physical, VK_FORMAT_R32G32B32A32_SFLOAT, format);
            int required =
                    VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if (resolution > 1) {
                required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            }
            if ((format.optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException(
                        "Fog lighting requires linearly filtered RGBA32F storage images");
            }
            createImage(stack);
            createView(stack);
            createSampler(stack);
            gpu.resources.add(this);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void createImage(MemoryStack stack) {
        var handle = stack.longs(0);
        var create =
                VkImageCreateInfo.calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_3D)
                        .format(VK_FORMAT_R32G32B32A32_SFLOAT)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        create.extent().set(resolution * 2, resolution * 3, resolution);
        VulkanDevice.check(vkCreateImage(gpu.device, create, null, handle), "fog lighting image");
        image = handle.get(0);
        var requirements = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(gpu.device, image, requirements);
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(gpu.physical, properties);
        int type = -1;
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((requirements.memoryTypeBits() & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags()
                                    & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                            != 0) {
                type = index;
                break;
            }
        }
        if (type < 0) {
            throw new IllegalStateException("Fog lighting device memory unavailable");
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
                "fog lighting allocation");
        memory = handle.get(0);
        bytes = requirements.size();
        gpu.allocatedBytes += bytes;
        gpu.peakBytes = Math.max(gpu.peakBytes, gpu.allocatedBytes);
        VulkanDevice.check(vkBindImageMemory(gpu.device, image, memory, 0), "fog lighting bind");
    }

    private void createView(MemoryStack stack) {
        var create =
                VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_3D)
                        .format(VK_FORMAT_R32G32B32A32_SFLOAT);
        create.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateImageView(gpu.device, create, null, handle), "fog lighting view");
        view = handle.get(0);
    }

    private void createSampler(MemoryStack stack) {
        var create =
                VkSamplerCreateInfo.calloc(stack)
                        .sType$Default()
                        .magFilter(VK_FILTER_LINEAR)
                        .minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateSampler(gpu.device, create, null, handle), "fog lighting sampler");
        sampler = handle.get(0);
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
                            .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .layerCount(1);
            vkCmdPipelineBarrier(
                    gpu.command,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier);
        }
        initialized = true;
    }

    @Override
    public void close() {
        if (sampler != 0) {
            vkDestroySampler(gpu.device, sampler, null);
            sampler = 0;
        }
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
