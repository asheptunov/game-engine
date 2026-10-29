package gpu;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/** One cached NRD compute pipeline; binding numbers come from the pinned library ABI. */
final class GpuNrdPipeline implements AutoCloseable {
    private final VulkanDevice gpu;
    final long[] layouts = new long[2];
    final int sampled;
    final int storage;
    long layout;
    long pipeline;

    GpuNrdPipeline(VulkanDevice gpu, GpuNrdLibrary library, int index) {
        this.gpu = gpu;
        var description = library.pipeline(index);
        sampled = description.textures();
        storage = description.storage();
        int[] info = library.info;
        if (info[4] != 1 || info[5] != 0) {
            throw new IllegalStateException("Unexpected NRD descriptor spaces");
        }
        try (var stack = MemoryStack.stackPush()) {
            var resources = VkDescriptorSetLayoutBinding.calloc(sampled + storage, stack);
            for (int i = 0; i < sampled; i++) {
                binding(resources.get(i), info[8] + i, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
            }
            for (int i = 0; i < storage; i++) {
                binding(resources.get(sampled + i), info[9] + i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            layouts[0] = descriptorLayout(gpu, resources, stack);
            var constants = VkDescriptorSetLayoutBinding.calloc(3, stack);
            binding(constants.get(0), info[6], VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            binding(constants.get(1), info[7], VK_DESCRIPTOR_TYPE_SAMPLER);
            binding(constants.get(2), info[7] + 1, VK_DESCRIPTOR_TYPE_SAMPLER);
            layouts[1] = descriptorLayout(gpu, constants, stack);
            var handle = stack.longs(0);
            VulkanDevice.check(
                    vkCreatePipelineLayout(
                            gpu.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(layouts)),
                            null,
                            handle),
                    "NRD pipeline layout");
            layout = handle.get(0);
            VulkanDevice.check(
                    vkCreateShaderModule(
                            gpu.device,
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(description.shader()),
                            null,
                            handle),
                    "NRD shader module");
            long shader = handle.get(0);
            try {
                var create = VkComputePipelineCreateInfo.calloc(1, stack);
                create.get(0)
                        .sType$Default()
                        .layout(layout)
                        .stage()
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(shader)
                        .pName(stack.UTF8("main"));
                VulkanDevice.check(
                        vkCreateComputePipelines(gpu.device, 0, create, null, handle),
                        "NRD compute pipeline");
                pipeline = handle.get(0);
            } finally {
                vkDestroyShaderModule(gpu.device, shader, null);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static void binding(VkDescriptorSetLayoutBinding target, int index, int type) {
        target.binding(index)
                .descriptorType(type)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
    }

    static long descriptorLayout(
            VulkanDevice gpu, VkDescriptorSetLayoutBinding.Buffer bindings, MemoryStack stack) {
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateDescriptorSetLayout(
                        gpu.device,
                        VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pBindings(bindings),
                        null,
                        handle),
                "NRD descriptor layout");
        return handle.get(0);
    }

    public void close() {
        if (pipeline != 0) {
            vkDestroyPipeline(gpu.device, pipeline, null);
            pipeline = 0;
        }
        if (layout != 0) {
            vkDestroyPipelineLayout(gpu.device, layout, null);
            layout = 0;
        }
        for (int i = 0; i < layouts.length; i++) {
            if (layouts[i] != 0) {
                vkDestroyDescriptorSetLayout(gpu.device, layouts[i], null);
                layouts[i] = 0;
            }
        }
    }
}
