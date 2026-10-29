package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_UNIFORM_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkResetDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Executes NRD's own schedule. All resources and descriptors belong to the GPU owner thread. */
final class GpuNrdPass implements AutoCloseable {
    private static final int MAX_DISPATCHES = 64;
    private final VulkanDevice gpu;
    private final GpuNrdLibrary library;
    private final GpuNrdPipeline[] pipelines;
    private final GpuNrdImage[] permanent;
    private final GpuNrdImage[] transientImages;
    final GpuNrdImage[] signals = new GpuNrdImage[6];
    private VulkanDevice.Buffer constants;
    private final long[] samplers = new long[2];
    private long descriptorPool;
    private int constantStride;
    private int dispatchCount;
    private int[][] dispatches;
    private boolean closed;

    GpuNrdPass(VulkanDevice gpu, int width, int height) {
        this.gpu = gpu;
        library = new GpuNrdLibrary();
        pipelines = new GpuNrdPipeline[library.info[0]];
        permanent = new GpuNrdImage[library.info[1]];
        transientImages = new GpuNrdImage[library.info[2]];
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(gpu.physical, properties);
            long alignment = properties.limits().minUniformBufferOffsetAlignment();
            constantStride = (int) ((library.info[3] + alignment - 1) / alignment * alignment);
            constants =
                    gpu.buffer(
                            (long) constantStride * MAX_DISPATCHES,
                            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                            true);
            int[] formats = {
                VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                VK_FORMAT_R32_SFLOAT,
                VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_R8_UNORM,
                VK_FORMAT_R16G16B16A16_SFLOAT
            };
            for (int i = 0; i < signals.length; i++) {
                signals[i] = new GpuNrdImage(gpu, width, height, formats[i]);
            }
            createTextures(permanent, true, width, height);
            createTextures(transientImages, false, width, height);
            for (int i = 0; i < pipelines.length; i++) {
                pipelines[i] = new GpuNrdPipeline(gpu, library, i);
            }
            createDescriptors(stack);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void createTextures(GpuNrdImage[] images, boolean persistent, int width, int height) {
        for (int i = 0; i < images.length; i++) {
            int encoded = library.texture(persistent, i);
            int downsample = encoded >>> 16;
            int format =
                    switch (encoded & 65535) {
                        case 0 -> VK_FORMAT_R8_UNORM;
                        case 8 -> VK_FORMAT_R8G8B8A8_UNORM;
                        case 27 -> VK_FORMAT_R16G16B16A16_SFLOAT;
                        case 30 -> VK_FORMAT_R32_SFLOAT;
                        default ->
                                throw new IllegalStateException(
                                        "Unexpected NRD texture format: " + encoded);
                    };
            images[i] =
                    new GpuNrdImage(
                            gpu,
                            (width + downsample - 1) / downsample,
                            (height + downsample - 1) / downsample,
                            format);
        }
    }

    private void createDescriptors(MemoryStack stack) {
        var handle = stack.longs(0);
        for (int i = 0; i < samplers.length; i++) {
            int filter = i == 0 ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
            var create =
                    VkSamplerCreateInfo.calloc(stack)
                            .sType$Default()
                            .magFilter(filter)
                            .minFilter(filter)
                            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            VulkanDevice.check(vkCreateSampler(gpu.device, create, null, handle), "NRD sampler");
            samplers[i] = handle.get(0);
        }
        var sizes = VkDescriptorPoolSize.calloc(4, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(MAX_DISPATCHES * 20);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(MAX_DISPATCHES * 20);
        sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_DISPATCHES);
        sizes.get(3).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(MAX_DISPATCHES * 2);
        VulkanDevice.check(
                vkCreateDescriptorPool(
                        gpu.device,
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .pPoolSizes(sizes)
                                .maxSets(MAX_DISPATCHES * 2),
                        null,
                        handle),
                "NRD descriptor pool");
        descriptorPool = handle.get(0);
    }

    void prepare(float[] matrices, int width, int height, int frame, boolean reset, float millis) {
        dispatchCount = library.frame(matrices, width, height, frame, reset, millis);
        if (dispatchCount > MAX_DISPATCHES) {
            throw new IllegalStateException("NRD dispatch capacity exceeded");
        }
        dispatches = new int[dispatchCount][];
        for (int i = 0; i < dispatchCount; i++) {
            var bytes = constants.mapped.slice(i * constantStride, library.info[3]);
            dispatches[i] = library.dispatch(i, bytes);
        }
    }

    void initializeImages() {
        for (var image : signals) {
            image.prepare();
        }
        for (var image : permanent) {
            image.prepare();
        }
        for (var image : transientImages) {
            image.prepare();
        }
        barrier();
    }

    void record() {
        VulkanDevice.check(
                vkResetDescriptorPool(gpu.device, descriptorPool, 0), "NRD descriptor reset");
        for (int index = 0; index < dispatchCount; index++) {
            try (var stack = MemoryStack.stackPush()) {
                int[] dispatch = dispatches[index];
                var pipeline = pipelines[dispatch[0]];
                var sets = stack.mallocLong(2);
                VulkanDevice.check(
                        vkAllocateDescriptorSets(
                                gpu.device,
                                VkDescriptorSetAllocateInfo.calloc(stack)
                                        .sType$Default()
                                        .descriptorPool(descriptorPool)
                                        .pSetLayouts(stack.longs(pipeline.layouts)),
                                sets),
                        "NRD dispatch sets");
                var writes = VkWriteDescriptorSet.calloc(dispatch[3] + 3, stack);
                int readIndex = 0;
                int writeIndex = 0;
                for (int resource = 0; resource < dispatch[3]; resource++) {
                    int offset = 5 + resource * 3;
                    var image = resource(dispatch[offset], dispatch[offset + 1]);
                    boolean read = dispatch[offset + 2] == 0;
                    int binding =
                            read ? library.info[8] + readIndex++ : library.info[9] + writeIndex++;
                    writes.get(resource)
                            .sType$Default()
                            .dstSet(sets.get(0))
                            .dstBinding(binding)
                            .descriptorCount(1)
                            .descriptorType(
                                    read
                                            ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                                            : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(
                                    VkDescriptorImageInfo.calloc(1, stack)
                                            .imageView(image.view)
                                            .imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
                writes.get(dispatch[3])
                        .sType$Default()
                        .dstSet(sets.get(1))
                        .dstBinding(library.info[6])
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(
                                VkDescriptorBufferInfo.calloc(1, stack)
                                        .buffer(constants.handle)
                                        .offset((long) index * constantStride)
                                        .range(library.info[3]));
                for (int i = 0; i < samplers.length; i++) {
                    writes.get(dispatch[3] + 1 + i)
                            .sType$Default()
                            .dstSet(sets.get(1))
                            .dstBinding(library.info[7] + i)
                            .descriptorCount(1)
                            .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER)
                            .pImageInfo(
                                    VkDescriptorImageInfo.calloc(1, stack).sampler(samplers[i]));
                }
                vkUpdateDescriptorSets(gpu.device, writes, null);
                barrier();
                vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline);
                vkCmdBindDescriptorSets(
                        gpu.command,
                        VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipeline.layout,
                        0,
                        sets,
                        null);
                vkCmdDispatch(gpu.command, dispatch[1], dispatch[2], 1);
            }
        }
        barrier();
    }

    private GpuNrdImage resource(int semantic, int index) {
        return switch (semantic) {
            case 6 -> transientImages[index];
            case 7 -> permanent[index];
            default -> signals[semantic];
        };
    }

    private void barrier() {
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_TRANSFER_BIT
                        | VK_PIPELINE_STAGE_HOST_BIT,
                VK_ACCESS_SHADER_WRITE_BIT
                        | VK_ACCESS_TRANSFER_WRITE_BIT
                        | VK_ACCESS_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT
                        | VK_ACCESS_SHADER_WRITE_BIT
                        | VK_ACCESS_UNIFORM_READ_BIT);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var pipeline : pipelines) {
            if (pipeline != null) {
                pipeline.close();
            }
        }
        closeImages(signals);
        closeImages(permanent);
        closeImages(transientImages);
        if (constants != null) {
            constants.close();
        }
        for (long sampler : samplers) {
            if (sampler != 0) {
                vkDestroySampler(gpu.device, sampler, null);
            }
        }
        if (descriptorPool != 0) {
            vkDestroyDescriptorPool(gpu.device, descriptorPool, null);
        }
        library.close();
    }

    private static void closeImages(GpuNrdImage[] images) {
        for (var image : images) {
            if (image != null) {
                image.close();
            }
        }
    }
}
