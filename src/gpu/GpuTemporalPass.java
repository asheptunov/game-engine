package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
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
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.file.Files;
import java.nio.file.Path;

/** One owner-thread storage-buffer compute pipeline for temporal reconstruction. */
final class GpuTemporalPass implements AutoCloseable {
    private final VulkanDevice gpu;
    private final int bindingsCount = 7;
    private long layout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private long set;

    GpuTemporalPass(VulkanDevice gpu) {
        this.gpu = gpu;
        try {
            create();
        } catch (Exception | Error failure) {
            close();
            throw new IllegalStateException("GPU temporal initialization failed", failure);
        }
    }

    private void create() throws java.io.IOException {
        try (var stack = MemoryStack.stackPush()) {
            createLayouts(stack);
            createPipeline(stack);
            createDescriptors(stack);
        }
    }

    private void createLayouts(MemoryStack stack) {
        var bindings = VkDescriptorSetLayoutBinding.calloc(bindingsCount, stack);
        for (int i = 0; i < bindingsCount; i++) {
            bindings.get(i)
                    .binding(i)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        }
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateDescriptorSetLayout(
                        gpu.device,
                        VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pBindings(bindings),
                        null,
                        handle),
                "temporal descriptor layout");
        layout = handle.get(0);
        var push =
                VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .size(16);
        VulkanDevice.check(
                vkCreatePipelineLayout(
                        gpu.device,
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pSetLayouts(stack.longs(layout))
                                .pPushConstantRanges(push),
                        null,
                        handle),
                "temporal pipeline layout");
        pipelineLayout = handle.get(0);
    }

    private void createPipeline(MemoryStack stack) throws java.io.IOException {
        var handle = stack.longs(0);
        byte[] source = Files.readAllBytes(Path.of("out/gpu/temporal.spv"));
        var code = MemoryUtil.memAlloc(source.length);
        long shader;
        try {
            code.put(source).flip();
            VulkanDevice.check(
                    vkCreateShaderModule(
                            gpu.device,
                            VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                            null,
                            handle),
                    "temporal shader");
            shader = handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
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
                    "temporal pipeline");
            pipeline = handle.get(0);
        } finally {
            vkDestroyShaderModule(gpu.device, shader, null);
        }
    }

    private void createDescriptors(MemoryStack stack) {
        var handle = stack.longs(0);
        var sizes =
                VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(bindingsCount);
        VulkanDevice.check(
                vkCreateDescriptorPool(
                        gpu.device,
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .maxSets(1)
                                .pPoolSizes(sizes),
                        null,
                        handle),
                "temporal descriptor pool");
        pool = handle.get(0);
        VulkanDevice.check(
                vkAllocateDescriptorSets(
                        gpu.device,
                        VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType$Default()
                                .descriptorPool(pool)
                                .pSetLayouts(stack.longs(layout)),
                        handle),
                "temporal descriptor set");
        set = handle.get(0);
    }

    void record(VulkanDevice.Buffer[] buffers, int width, int height, int samples, boolean valid) {
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(bindingsCount, stack);
            for (int i = 0; i < buffers.length; i++) {
                var info =
                        VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(buffers[i].handle)
                                .range(buffers[i].size);
                writes.get(i)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(i)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(info);
            }
            vkUpdateDescriptorSets(gpu.device, writes, null);
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
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
            var push =
                    stack.malloc(16)
                            .putInt(0, width)
                            .putInt(4, height)
                            .putInt(8, samples)
                            .putInt(12, valid ? 1 : 0);
            vkCmdPushConstants(gpu.command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            org.lwjgl.vulkan.VK10.vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    @Override
    public void close() {
        if (pipeline != 0) {
            vkDestroyPipeline(gpu.device, pipeline, null);
        }
        if (pool != 0) {
            vkDestroyDescriptorPool(gpu.device, pool, null);
        }
        if (pipelineLayout != 0) {
            vkDestroyPipelineLayout(gpu.device, pipelineLayout, null);
        }
        if (layout != 0) {
            vkDestroyDescriptorSetLayout(gpu.device, layout, null);
        }
    }
}
