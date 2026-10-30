package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compute conversion with storage buffers followed by storage images in binding order. */
final class GpuImagePass implements AutoCloseable {
    private final VulkanDevice gpu;
    private long descriptorLayout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private long set;
    private final int bufferCount;
    private final int imageCount;

    GpuImagePass(VulkanDevice gpu, Path shader, int bufferCount, int imageCount, int pushBytes) {
        this.gpu = gpu;
        this.bufferCount = bufferCount;
        this.imageCount = imageCount;
        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(bufferCount + imageCount, stack);
            for (int i = 0; i < bufferCount + imageCount; i++) {
                GpuNrdPipeline.binding(
                        bindings.get(i),
                        i,
                        i < bufferCount
                                ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                                : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            descriptorLayout = GpuNrdPipeline.descriptorLayout(gpu, bindings, stack);
            var handle = stack.longs(0);
            var push =
                    VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                            .size(pushBytes);
            VulkanDevice.check(
                    vkCreatePipelineLayout(
                            gpu.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(descriptorLayout))
                                    .pPushConstantRanges(push),
                            null,
                            handle),
                    "GPU image conversion layout");
            pipelineLayout = handle.get(0);
            createShader(shader, stack);
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(bufferCount);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(imageCount);
            VulkanDevice.check(
                    vkCreateDescriptorPool(
                            gpu.device,
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(sizes),
                            null,
                            handle),
                    "GPU image conversion pool");
            pool = handle.get(0);
            VulkanDevice.check(
                    vkAllocateDescriptorSets(
                            gpu.device,
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(descriptorLayout)),
                            handle),
                    "GPU image conversion set");
            set = handle.get(0);
        } catch (java.io.IOException | RuntimeException | Error failure) {
            close();
            throw new IllegalStateException("GPU image conversion initialization failed", failure);
        }
    }

    private void createShader(Path path, MemoryStack stack) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(path);
        var code = MemoryUtil.memAlloc(bytes.length);
        var handle = stack.longs(0);
        try {
            code.put(bytes).flip();
            VulkanDevice.check(
                    vkCreateShaderModule(
                            gpu.device,
                            VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                            null,
                            handle),
                    "GPU image conversion shader");
        } finally {
            MemoryUtil.memFree(code);
        }
        long shader = handle.get(0);
        try {
            var create = VkComputePipelineCreateInfo.calloc(1, stack);
            create.get(0)
                    .sType$Default()
                    .layout(pipelineLayout)
                    .stage()
                    .sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shader)
                    .pName(stack.UTF8("main"));
            VulkanDevice.check(
                    vkCreateComputePipelines(gpu.device, 0, create, null, handle),
                    "GPU image conversion pipeline");
            pipeline = handle.get(0);
        } finally {
            vkDestroyShaderModule(gpu.device, shader, null);
        }
    }

    void bind(VulkanDevice.Buffer[] buffers, GpuImage[] images) {
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(bufferCount + imageCount, stack);
            for (int i = 0; i < bufferCount + imageCount; i++) {
                var write =
                        writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1);
                if (i < bufferCount) {
                    write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(
                                    VkDescriptorBufferInfo.calloc(1, stack)
                                            .buffer(buffers[i].handle)
                                            .range(buffers[i].size));
                } else {
                    write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(
                                    VkDescriptorImageInfo.calloc(1, stack)
                                            .imageView(images[i - bufferCount].view)
                                            .imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
            }
            vkUpdateDescriptorSets(gpu.device, writes, null);
        }
    }

    void record(int width, int height, ByteBuffer push) {
        try (var stack = MemoryStack.stackPush()) {
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(
                    gpu.command,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(set),
                    null);
            vkCmdPushConstants(gpu.command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    public void close() {
        if (pipeline != 0) {
            vkDestroyPipeline(gpu.device, pipeline, null);
            pipeline = 0;
        }
        if (pool != 0) {
            vkDestroyDescriptorPool(gpu.device, pool, null);
            pool = 0;
        }
        if (pipelineLayout != 0) {
            vkDestroyPipelineLayout(gpu.device, pipelineLayout, null);
            pipelineLayout = 0;
        }
        if (descriptorLayout != 0) {
            vkDestroyDescriptorSetLayout(gpu.device, descriptorLayout, null);
            descriptorLayout = 0;
        }
    }
}
