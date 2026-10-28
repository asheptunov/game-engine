package gpu;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
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
import org.lwjgl.vulkan.VkBufferCopy;
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

/** Owner-thread display conversion. Raw snapshots stay on the device until explicitly read. */
final class GpuDisplay implements AutoCloseable {
    private final VulkanDevice gpu;
    private long layout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private long set;
    private VulkanDevice.Buffer table;
    private VulkanDevice.Buffer packed;
    private VulkanDevice.Buffer staging;

    GpuDisplay(VulkanDevice gpu) {
        this.gpu = gpu;
        try {
            create();
        } catch (Exception | Error failure) {
            close();
            throw new IllegalStateException("GPU display initialization failed", failure);
        }
    }

    private void create() throws java.io.IOException {
        try (var stack = MemoryStack.stackPush()) {
            createLayouts(stack);
            createPipeline(stack);
            createDescriptors(stack);
        }
        createEncodingTable();
    }

    private void createLayouts(MemoryStack stack) {
        var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
        for (int i = 0; i < 3; i++) {
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
                "display descriptor layout");
        layout = handle.get(0);
        var push =
                VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .size(12);
        VulkanDevice.check(
                vkCreatePipelineLayout(
                        gpu.device,
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pSetLayouts(stack.longs(layout))
                                .pPushConstantRanges(push),
                        null,
                        handle),
                "display pipeline layout");
        pipelineLayout = handle.get(0);
    }

    private void createPipeline(MemoryStack stack) throws java.io.IOException {
        var handle = stack.longs(0);
        byte[] source = Files.readAllBytes(Path.of("out/gpu/display.spv"));
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
                    "display shader");
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
                    "display pipeline");
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
                        .descriptorCount(3);
        VulkanDevice.check(
                vkCreateDescriptorPool(
                        gpu.device,
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .maxSets(1)
                                .pPoolSizes(sizes),
                        null,
                        handle),
                "display descriptor pool");
        pool = handle.get(0);
        VulkanDevice.check(
                vkAllocateDescriptorSets(
                        gpu.device,
                        VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType$Default()
                                .descriptorPool(pool)
                                .pSetLayouts(stack.longs(layout)),
                        handle),
                "display descriptor set");
        set = handle.get(0);
    }

    private void createEncodingTable() {
        table = gpu.buffer(65536L * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        for (int i = 0; i < 65536; i++) {
            double c = i / 65535.;
            int value =
                    (int)
                            Math.round(
                                    255
                                            * (c <= .0031308
                                                    ? 12.92 * c
                                                    : 1.055 * Math.pow(c, 1 / 2.4) - .055));
            table.mapped.putInt(i * 4, value);
        }
    }

    void prepare(int count) {
        long bytes = (count + 1L) * 4;
        if (packed != null && packed.size == bytes) {
            return;
        }
        if (packed != null) {
            packed.close();
            staging.close();
        }
        packed =
                gpu.buffer(
                        bytes,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        false);
        staging = gpu.buffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
    }

    void encode(VulkanDevice.Buffer source, int width, int height, float exposure) {
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(3, stack);
            var buffers = new VulkanDevice.Buffer[] {source, packed, table};
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
            vkCmdFillBuffer(gpu.command, packed.handle, (long) width * height * 4, 4, 0);
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
            var push = stack.malloc(12).putInt(0, width).putInt(4, height).putFloat(8, exposure);
            vkCmdPushConstants(gpu.command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(gpu.command, (width * height + 63) / 64, 1, 1);
        }
    }

    void transfer() {
        gpu.imageReadbackBytes += packed.size;
        try (var stack = MemoryStack.stackPush()) {
            vkCmdCopyBuffer(
                    gpu.command,
                    packed.handle,
                    staging.handle,
                    VkBufferCopy.calloc(1, stack).size(packed.size));
        }
    }

    int read(int[] destination) {
        staging.mapped.asIntBuffer().get(destination);
        return staging.mapped.getInt(destination.length * 4);
    }

    @Override
    public void close() {
        if (packed != null) {
            packed.close();
        }
        if (staging != null) {
            staging.close();
        }
        if (table != null) {
            table.close();
        }
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
