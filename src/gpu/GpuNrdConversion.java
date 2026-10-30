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

import engine.Camera;

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

import java.nio.file.Files;
import java.nio.file.Path;

/** GPU-only packing and composition between the tracer's buffers and NRD's textures. */
final class GpuNrdConversion implements AutoCloseable {
    private final VulkanDevice gpu;
    private long descriptorLayout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private long set;

    GpuNrdConversion(VulkanDevice gpu) {
        this.gpu = gpu;
        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(11, stack);
            for (int i = 0; i < 11; i++) {
                GpuNrdPipeline.binding(
                        bindings.get(i),
                        i,
                        i < 5
                                ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                                : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            descriptorLayout = GpuNrdPipeline.descriptorLayout(gpu, bindings, stack);
            var handle = stack.longs(0);
            var push =
                    VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                            .size(48);
            VulkanDevice.check(
                    vkCreatePipelineLayout(
                            gpu.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(descriptorLayout))
                                    .pPushConstantRanges(push),
                            null,
                            handle),
                    "NRD conversion layout");
            pipelineLayout = handle.get(0);
            createShader(stack);
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(6);
            VulkanDevice.check(
                    vkCreateDescriptorPool(
                            gpu.device,
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(sizes),
                            null,
                            handle),
                    "NRD conversion pool");
            pool = handle.get(0);
            VulkanDevice.check(
                    vkAllocateDescriptorSets(
                            gpu.device,
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(descriptorLayout)),
                            handle),
                    "NRD conversion set");
            set = handle.get(0);
        } catch (java.io.IOException | RuntimeException | Error failure) {
            close();
            throw new IllegalStateException("NRD conversion initialization failed", failure);
        }
    }

    private void createShader(MemoryStack stack) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(Path.of("out/gpu/nrd.spv"));
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
                    "NRD conversion shader");
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
                    "NRD conversion pipeline");
            pipeline = handle.get(0);
        } finally {
            vkDestroyShaderModule(gpu.device, shader, null);
        }
    }

    void bind(VulkanDevice.Buffer[] buffers, GpuNrdImage[] images) {
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(11, stack);
            for (int i = 0; i < 11; i++) {
                var write =
                        writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1);
                if (i < 5) {
                    write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(
                                    VkDescriptorBufferInfo.calloc(1, stack)
                                            .buffer(buffers[i].handle)
                                            .range(buffers[i].size));
                } else {
                    write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(
                                    VkDescriptorImageInfo.calloc(1, stack)
                                            .imageView(images[i - 5].view)
                                            .imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
            }
            vkUpdateDescriptorSets(gpu.device, writes, null);
        }
    }

    void record(int width, int height, Camera camera, boolean compose, float confidence) {
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
            var push =
                    stack.calloc(48).putInt(0, width).putInt(4, height).putInt(8, compose ? 1 : 0);
            var eye = camera.eye();
            var forward = GpuNrdCamera.forward(camera);
            push.putFloat(16, eye.x()).putFloat(20, eye.y()).putFloat(24, eye.z());
            push.putFloat(32, forward.x()).putFloat(36, forward.y()).putFloat(40, forward.z());
            push.putFloat(44, confidence);
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
