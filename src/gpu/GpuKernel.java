package gpu;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;
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
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

import engine.Camera;
import engine.RenderSettings;
import engine.RenderView;
import engine.WorldSnapshot;
import engine.lights.DirectionalLight;

import math.Vec3;

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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One complete sample dispatch with resident double-precision means and explicit readback. */
final class GpuKernel implements AutoCloseable {
    final VulkanDevice gpu;
    final long initializationNanos;
    private final Runnable beforeDispatch;
    // Comparison mode retains the original all-candidate traversal, including exact ties.
    private final boolean referenceTraversal = Boolean.getBoolean("gpu.referenceTraversal");
    GpuScene scene;
    GpuTemporal temporal;
    GpuLighting lighting;
    long visibilityNanos;
    private final long[] reconstructionPipelines = new long[2];
    private final long[] visibilityPipelines = new long[2];
    private final long[] fallbackPipelines = new long[2];
    private long queuePipeline;
    long reconstructionNanos;
    private final long[] temporalPipelines = new long[2];
    private WorldSnapshot compiledWorld;
    private long descriptorLayout;
    private long pipelineLayout;
    private long pipeline;
    private long fogSunPipeline;
    private long fogPrimaryPipeline;
    private long fogReconstructPipeline;
    private long fogResolvePipeline;
    private long lightingBuildPipeline;
    private final long[] foveatedPipelines = new long[2];
    private final GpuFoveation foveation = new GpuFoveation();
    private final GpuSmoothFoveation smoothFoveation = new GpuSmoothFoveation();
    private long smoothResolvePipeline;
    private final long[] lightingPipelines = new long[5];
    private long descriptorPool;
    private long descriptorSet;
    private VulkanDevice.Buffer parameters;
    private engine.AtmosphereTable uploadedAtmosphere;
    private VulkanDevice.Buffer fogSun;
    private engine.FogSunTable.Layout fogLayout;
    private VulkanDevice.Buffer fogPrimary;
    private VulkanDevice.Buffer fogGuides;
    private FogLightingTexture lightingTexture;
    boolean lightingGrid;
    long lightingBuildNanos;
    int fogDownsample = 1;
    long fogPrimaryNanos;
    int fogReusedPixels;
    private VulkanDevice.Buffer mean;
    private VulkanDevice.Buffer output;
    private VulkanDevice.Buffer readback;
    private GpuDisplay display;
    private int width;
    private int height;
    long executeNanos;
    long fogBuildNanos;
    long fogBuilds;
    long transferNanos;
    long hostNanos;
    long submitWaitNanos;
    long readbackNanos;
    long prepareNanos;
    long maximumPrepareNanos;

    GpuKernel() {
        this(() -> {});
    }

    GpuKernel(Runnable beforeDispatch) {
        this(beforeDispatch, Path.of("out/gpu/diffuse.spv"));
    }

    /** Package-local benchmark seam; diagnostic shaders never replace the installed shader. */
    GpuKernel(Runnable beforeDispatch, Path shaderPath) {
        long started = System.nanoTime();
        this.beforeDispatch = beforeDispatch;
        gpu = new VulkanDevice();
        try {
            parameters =
                    gpu.buffer(
                            304 + engine.AtmosphereTable.FLOATS * 4 + 16 + 8 * 96 + 32,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            true);
            fogSun =
                    gpu.buffer(
                            (long) engine.FogSunTable.CELLS * 16,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            false);
            temporal = new GpuTemporal(gpu);
            lighting = new GpuLighting(gpu);
            createPipeline(shaderPath);
            initializationNanos = System.nanoTime() - started;
        } catch (RuntimeException | Error failure) {
            var reported =
                    new engine.RenderFailure(
                            "gpu",
                            "initialization",
                            failure,
                            java.util.Map.of(
                                    "device",
                                    gpu.description,
                                    "initialization",
                                    "device ready; pipeline incomplete"));
            FailedInitialization.retire(failure, this::close);
            throw reported;
        }
    }

    static void validate(WorldSnapshot world, RenderView view, RenderSettings settings) {
        engine.RenderCapabilities.GPU.validate(world, view, settings);
    }

    private void createPipeline(Path shaderPath) {
        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(23, stack);
            for (int i = 0; i < 23; i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorCount(1)
                        .descriptorType(
                                i == 0
                                        ? VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
                                        : i == 10
                                                ? org.lwjgl
                                                        .vulkan
                                                        .VK10
                                                        .VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                                : i == 11
                                                        ? org.lwjgl
                                                                .vulkan
                                                                .VK10
                                                                .VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                                        : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
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
                    "descriptor layout");
            descriptorLayout = handle.get(0);
            VulkanDevice.check(
                    vkCreatePipelineLayout(
                            gpu.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(descriptorLayout))
                                    .pPushConstantRanges(
                                            org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack)
                                                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                                                    .size(16)),
                            null,
                            handle),
                    "pipeline layout");
            pipelineLayout = handle.get(0);
            byte[] source;
            try {
                source = Files.readAllBytes(shaderPath);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Run ./gpu-build.ps1 to build GPU shaders", failure);
            }
            var code = MemoryUtil.memAlloc(source.length);
            code.put(source).flip();
            long shader;
            try {
                VulkanDevice.check(
                        vkCreateShaderModule(
                                gpu.device,
                                VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                                null,
                                handle),
                        "shader module");
                shader = handle.get(0);
            } finally {
                MemoryUtil.memFree(code);
            }
            try {
                pipeline = createVariant(shader, 0, stack);
                for (int i = 0; i < 2; i++) {
                    reconstructionPipelines[i] =
                            createVariant(shader, 0, stack, i == 1, false, true, true);
                    visibilityPipelines[i] =
                            createVariant(shader, 7, stack, i == 1, false, false, true);
                    fallbackPipelines[i] =
                            createVariant(shader, 8, stack, i == 1, false, false, true);
                }
                queuePipeline = createVariant(shader, 9, stack);
                temporalPipelines[0] = createVariant(shader, 0, stack, false, false, true);
                temporalPipelines[1] = createVariant(shader, 0, stack, true, false, true);
                foveatedPipelines[0] = createVariant(shader, 0, stack, false, true);
                foveatedPipelines[1] = createVariant(shader, 0, stack, true, true);
                fogSunPipeline = createVariant(shader, 1, stack);
                fogPrimaryPipeline = createVariant(shader, 2, stack);
                fogReconstructPipeline = createVariant(shader, 3, stack);
                fogResolvePipeline = createVariant(shader, 4, stack);
                lightingBuildPipeline = createVariant(shader, 5, stack);
                smoothResolvePipeline = createVariant(shader, 6, stack, false, true);
                for (int mode : new int[] {0, 2, 3, 4}) {
                    lightingPipelines[mode] = createVariant(shader, mode, stack, true);
                }
            } finally {
                vkDestroyShaderModule(gpu.device, shader, null);
            }
            var sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(20);
            sizes.get(2)
                    .type(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1);
            sizes.get(3)
                    .type(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1);
            VulkanDevice.check(
                    vkCreateDescriptorPool(
                            gpu.device,
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(sizes),
                            null,
                            handle),
                    "descriptor pool");
            descriptorPool = handle.get(0);
            VulkanDevice.check(
                    vkAllocateDescriptorSets(
                            gpu.device,
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(stack.longs(descriptorLayout)),
                            handle),
                    "descriptor set");
            descriptorSet = handle.get(0);
        }
    }

    private long createVariant(long shader, int mode, MemoryStack stack) {
        return createVariant(shader, mode, stack, false);
    }

    private long createVariant(long shader, int mode, MemoryStack stack, boolean gridLighting) {
        return createVariant(shader, mode, stack, gridLighting, false);
    }

    private long createVariant(
            long shader, int mode, MemoryStack stack, boolean gridLighting, boolean foveated) {
        return createVariant(shader, mode, stack, gridLighting, foveated, false);
    }

    private long createVariant(
            long shader,
            int mode,
            MemoryStack stack,
            boolean gridLighting,
            boolean foveated,
            boolean guides) {
        return createVariant(shader, mode, stack, gridLighting, foveated, guides, false);
    }

    private long createVariant(
            long shader,
            int mode,
            MemoryStack stack,
            boolean gridLighting,
            boolean foveated,
            boolean guides,
            boolean reconstructLighting) {
        var constant = VkSpecializationMapEntry.calloc(6, stack);
        constant.get(0).constantID(0).offset(0).size(4);
        constant.get(1).constantID(1).offset(4).size(4);
        constant.get(2).constantID(2).offset(8).size(4);
        constant.get(3).constantID(3).offset(12).size(4);
        constant.get(4).constantID(4).offset(16).size(4);
        constant.get(5).constantID(5).offset(20).size(4);
        var data =
                stack.malloc(24)
                        .putInt(0, mode)
                        .putInt(4, gridLighting ? 1 : 0)
                        .putInt(8, foveated ? 1 : 0)
                        .putInt(12, referenceTraversal ? 1 : 0)
                        .putInt(16, guides ? 1 : 0)
                        .putInt(20, reconstructLighting ? 1 : 0);
        var specialization = VkSpecializationInfo.calloc(stack).pMapEntries(constant).pData(data);
        var create = VkComputePipelineCreateInfo.calloc(1, stack);
        create.get(0)
                .sType$Default()
                .layout(pipelineLayout)
                .stage()
                .sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shader)
                .pName(stack.UTF8("main"))
                .pSpecializationInfo(specialization);
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateComputePipelines(gpu.device, 0, create, null, handle), "compute pipeline");
        return handle.get(0);
    }

    void trace(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            long sample,
            float[] pixels) {
        trace(world, view, settings, sample, pixels, null, null, 1);
    }

    void trace(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            long sample,
            float[] pixels,
            int[] packed,
            Snapshot snapshot,
            float exposure) {
        trace(
                world,
                view,
                settings,
                sample,
                new Destination(pixels, packed, snapshot, exposure, null));
    }

    record Destination(
            float[] pixels,
            int[] packed,
            Snapshot snapshot,
            float exposure,
            Snapshot presentation,
            RenderView outputView) {
        Destination(
                float[] pixels,
                int[] packed,
                Snapshot snapshot,
                float exposure,
                Snapshot presentation) {
            this(pixels, packed, snapshot, exposure, presentation, null);
        }
    }

    void trace(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            long sample,
            Destination destination) {
        var pixels = destination.pixels();
        var packed = destination.packed();
        var snapshot = destination.snapshot();
        var presentation = destination.presentation();
        validate(world, view, settings);
        long start = System.nanoTime();
        var outputView = destination.outputView() == null ? view : destination.outputView();
        GpuLighting.validate(view, outputView, settings);
        prepareDisplay(view, outputView, packed, snapshot);
        boolean changed = prepareScene(world);
        changed |= prepareImages(view);
        changed |= prepareSampling(settings);
        changed |= temporal.prepare(world, view, settings, sample);
        changed |= lighting.prepare(view, outputView, temporal.nrd != null);
        if (presentation != null) {
            presentation.prepare(gpu, outputView.width() * outputView.height());
        }
        if (snapshot == null && readback == null) {
            readback = gpu.buffer(output.size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        }
        var nextFogLayout = engine.FogSunTable.layout(world.sky());
        changed |=
                preparePrimaryFog(
                        view,
                        settings,
                        nextFogLayout != null
                                || (world.sky().atmosphere() != null
                                        && world.sky().fog().isEmpty()));
        changed |=
                prepareLightingGrid(
                        settings.fogLightingGrid()
                                && engine.FogLightingGrid.eligible(
                                        world.sky().atmosphere(), nextFogLayout));
        if (changed) {
            bind();
        }
        prepareNanos = System.nanoTime() - start;
        maximumPrepareNanos = Math.max(maximumPrepareNanos, prepareNanos);
        writeParameters(world, view, settings, sample);
        boolean rebuildFog = nextFogLayout != null && !nextFogLayout.equals(fogLayout);
        fogLayout = nextFogLayout;
        writeFogLayout();
        parameters.mapped.putFloat(
                (19 + engine.AtmosphereTable.FLOATS / 4) * 16 + 4, settings.fogJitter() ? 1 : 0);
        beforeDispatch.run();
        gpu.begin();
        lightingTexture.prepare();
        vkCmdResetQueryPool(gpu.command, gpu.queries, 0, 10);
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, gpu.queries, 0);
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (var stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(
                    gpu.command,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSet),
                    null);
            buildFogTable(rebuildFog);
            vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 3);
            buildLightingGrid();
            vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 5);
            buildPrimaryFog();
            vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 4);
            dispatchMain(settings);
            resolvePrimaryFog();
            finishSample(destination, sample, rebuildFog, settings, stack);
        }
        long readbackStart = System.nanoTime();
        if (packed != null || snapshot == null) {
            readOutput(pixels, packed);
        } else {
            fogReusedPixels = -1;
        }
        readbackNanos = System.nanoTime() - readbackStart;
        hostNanos = System.nanoTime() - start;
    }

    private void finishSample(
            Destination destination,
            long sample,
            boolean rebuildFog,
            RenderSettings settings,
            MemoryStack stack) {
        var packed = destination.packed();
        var snapshot = destination.snapshot();
        var presentation = destination.presentation();
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 6);
        var shown = temporal.record(output, lighting.albedo, width, height, sample);
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 7);
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 8);
        if (lighting.active) {
            recordVisibility(sample, stack);
            shown = lighting.output;
        }
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 9);
        if (packed != null) {
            display.encode(
                    shown,
                    lighting.width,
                    lighting.height,
                    destination.exposure(),
                    settings.bloom());
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT);
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 1);
        transferOutput(snapshot, packed, stack);
        if (presentation != null) {
            vkCmdCopyBuffer(
                    gpu.command,
                    shown.handle,
                    presentation.source.handle,
                    VkBufferCopy.calloc(1, stack).size(shown.size));
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_ACCESS_HOST_READ_BIT);
        vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, gpu.queries, 2);
        long submitStart = System.nanoTime();
        gpu.submit();
        submitWaitNanos = System.nanoTime() - submitStart;
        var times = stack.mallocLong(10);
        VulkanDevice.check(
                vkGetQueryPoolResults(
                        gpu.device,
                        gpu.queries,
                        0,
                        10,
                        times,
                        8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                "read timestamps");
        recordTimings(rebuildFog, times);
        reconstructionNanos = (long) ((times.get(7) - times.get(6)) * gpu.timestampPeriod);
        visibilityNanos = (long) ((times.get(9) - times.get(8)) * gpu.timestampPeriod);
    }

    private void recordVisibility(long sample, MemoryStack stack) {
        lighting.clearFallback(sample);
        vkCmdBindPipeline(
                gpu.command,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                visibilityPipelines[lightingGrid ? 1 : 0]);
        vkCmdBindDescriptorSets(
                gpu.command,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout,
                0,
                stack.longs(descriptorSet),
                null);
        var push =
                stack.malloc(16)
                        .putInt(0, lighting.width)
                        .putInt(4, lighting.height)
                        .putInt(8, 0)
                        .putInt(12, 0);
        org.lwjgl.vulkan.VK10.vkCmdPushConstants(
                gpu.command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(gpu.command, (lighting.width + 7) / 8, (lighting.height + 7) / 8, 1);
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, queuePipeline);
        vkCmdDispatch(gpu.command, 1, 1, 1);
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK_ACCESS_SHADER_READ_BIT
                        | VK_ACCESS_SHADER_WRITE_BIT
                        | org.lwjgl.vulkan.VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        vkCmdBindPipeline(
                gpu.command,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                fallbackPipelines[lightingGrid ? 1 : 0]);
        org.lwjgl.vulkan.VK10.vkCmdDispatchIndirect(gpu.command, lighting.fallbackQueue.handle, 4);
    }

    private boolean prepareSampling(RenderSettings settings) {
        boolean changed =
                foveation.prepare(
                        gpu, width, height, settings.foveated() && !settings.smoothFoveation());
        changed |=
                smoothFoveation.prepare(
                        gpu,
                        width,
                        height,
                        settings.foveated() && settings.smoothFoveation(),
                        settings.foveationQuality());
        return changed;
    }

    private static int samplingMode(RenderSettings settings) {
        if (!settings.foveated()) {
            return 0;
        }
        if (settings.smoothFoveation()) {
            return 3;
        }
        return Boolean.getBoolean("gpu.sparseFoveation") ? 2 : 1;
    }

    private void dispatchMain(RenderSettings settings) {
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, mainPipeline(settings));
        if (settings.foveated() && settings.smoothFoveation()) {
            GpuDispatch.linear(gpu.command, smoothFoveation.count);
        } else if (settings.foveated() && !Boolean.getBoolean("gpu.sparseFoveation")) {
            GpuDispatch.linear(gpu.command, foveation.count);
        } else {
            vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
        }
        resolveSmoothFoveation(settings);
    }

    private long mainPipeline(RenderSettings settings) {
        if (lighting.active || temporal.nrd != null) {
            return reconstructionPipelines[lightingGrid ? 1 : 0];
        }
        if (temporal.active) {
            return temporalPipelines[lightingGrid ? 1 : 0];
        }
        if (settings.foveated()) {
            return foveatedPipelines[lightingGrid ? 1 : 0];
        }
        return fogDownsample == 1
                ? lightingVariant(pipeline, 0)
                : lightingVariant(fogReconstructPipeline, 3);
    }

    private void resolveSmoothFoveation(RenderSettings settings) {
        if (!settings.foveated() || !settings.smoothFoveation()) {
            return;
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT);
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, smoothResolvePipeline);
        vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
    }

    private void bindSmoothBuffers(VkWriteDescriptorSet.Buffer writes, MemoryStack stack) {
        var buffers = new VulkanDevice.Buffer[] {smoothFoveation.layout, smoothFoveation.guides};
        for (int i = 0; i < buffers.length; i++) {
            var info =
                    VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(buffers[i].handle)
                            .offset(0)
                            .range(buffers[i].size);
            writes.get(13 + i)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(13 + i)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(info);
        }
    }

    private void transferOutput(Snapshot snapshot, int[] packed, MemoryStack stack) {
        var copy = VkBufferCopy.calloc(1, stack).size(output.size);
        if (snapshot == null) {
            gpu.imageReadbackBytes += output.size;
            vkCmdCopyBuffer(gpu.command, output.handle, readback.handle, copy);
        } else {
            vkCmdCopyBuffer(gpu.command, output.handle, snapshot.source.handle, copy);
            if (packed != null) {
                display.transfer();
            }
        }
    }

    private void readOutput(float[] pixels, int[] packed) {
        fogReusedPixels = packed == null ? 0 : display.read(packed);
        for (int i = 0; packed == null && i < width * height; i++) {
            if (readback.mapped.getFloat(i * 16 + 12) > 0) {
                fogReusedPixels++;
            }
            pixels[i * 3] = readback.mapped.getFloat(i * 16);
            pixels[i * 3 + 1] = readback.mapped.getFloat(i * 16 + 4);
            pixels[i * 3 + 2] = readback.mapped.getFloat(i * 16 + 8);
        }
        // Native output alpha identifies lighting reuse for diagnostics, not the separate fog pass.
        if (lighting.active) {
            fogReusedPixels = 0;
        }
    }

    private void prepareDisplay(
            RenderView view, RenderView outputView, int[] packed, Snapshot snapshot) {
        if (packed != null) {
            if (display == null) {
                display = new GpuDisplay(gpu);
            }
            display.prepare(outputView.width() * outputView.height());
        }
        if (snapshot != null) {
            snapshot.prepare(gpu, view.width() * view.height());
        }
    }

    /** Bounded by the session's three publication slots; accesses run on its GPU owner. */
    static final class Snapshot {
        VulkanDevice.Buffer source;
        private float[] pixels;

        void prepare(VulkanDevice gpu, int count) {
            pixels = null;
            if (source == null || source.size != (long) count * 16) {
                if (source != null) {
                    source.close();
                }
                source =
                        gpu.buffer(
                                (long) count * 16,
                                VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                false);
            }
        }

        float[] read(VulkanDevice gpu) {
            if (pixels != null) {
                return pixels;
            }
            int count = (int) (source.size / 16);
            gpu.imageReadbackBytes += source.size;
            try (var staging = gpu.buffer(source.size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
                    var stack = MemoryStack.stackPush()) {
                gpu.begin();
                gpu.barrier(
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdCopyBuffer(
                        gpu.command,
                        source.handle,
                        staging.handle,
                        VkBufferCopy.calloc(1, stack).size(source.size));
                gpu.barrier(
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        VK_ACCESS_HOST_READ_BIT);
                gpu.submit();
                var result = new float[count * 3];
                for (int i = 0; i < count; i++) {
                    for (int c = 0; c < 3; c++) {
                        result[i * 3 + c] = staging.mapped.getFloat(i * 16 + c * 4);
                    }
                }
                pixels = result;
                return result;
            }
        }
    }

    private boolean prepareScene(WorldSnapshot world) {
        boolean changed = false;
        if (compiledWorld == null || !compiledWorld.instances().equals(world.instances())) {
            if (compiledWorld != null && sameGeometry(compiledWorld, world)) {
                scene.updateMaterials(world);
            } else if (scene != null && scene.containsGeometry(world)) {
                scene.updateInstances(compiledWorld, world);
            } else {
                if (scene != null) {
                    scene.close();
                    scene = null;
                }
                scene = new GpuScene(gpu, world);
            }
            compiledWorld = world;
            changed = true;
        }
        return changed;
    }

    private boolean prepareImages(RenderView view) {
        boolean changed = false;
        if (width != view.width() || height != view.height()) {
            closeImages();
            width = view.width();
            height = view.height();
            mean =
                    gpu.buffer(
                            (long) width * height * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            output =
                    gpu.buffer(
                            (long) width * height * 16,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            false);
            changed = true;
        }
        return changed;
    }

    private boolean preparePrimaryFog(
            RenderView view, RenderSettings settings, boolean eligibleAir) {
        boolean changed = false;
        // Atmosphere alone uses the same edge-checked primary-segment reconstruction.
        // Fog still requires its conservative layout eligibility; lenses always integrate directly.
        // Coarse spatial rays already integrate the complete segment for their footprint.
        fogDownsample =
                view.camera().aperture() == 0 && eligibleAir && !settings.foveated()
                        ? settings.fogDownsample()
                        : 1;
        long fogBytes =
                fogDownsample == 1
                        ? 48
                        : (long) ((width + fogDownsample - 1) / fogDownsample)
                                * ((height + fogDownsample - 1) / fogDownsample)
                                * 48;
        if (fogPrimary == null || fogPrimary.size != fogBytes) {
            if (fogPrimary != null) {
                fogPrimary.close();
            }
            fogPrimary = gpu.buffer(fogBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            changed = true;
        }
        long guideBytes = fogDownsample == 1 ? 32 : (long) width * height * 32;
        if (fogGuides == null || fogGuides.size != guideBytes) {
            if (fogGuides != null) {
                fogGuides.close();
            }
            fogGuides = gpu.buffer(guideBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            changed = true;
        }
        return changed;
    }

    private boolean prepareLightingGrid(boolean enabled) {
        lightingGrid = enabled;
        int resolution = enabled ? engine.FogSunTable.SIZE : 1;
        if (lightingTexture != null && lightingTexture.resolution == resolution) {
            return false;
        }
        if (lightingTexture != null) {
            lightingTexture.close();
        }
        lightingTexture = new FogLightingTexture(gpu, resolution);
        return true;
    }

    private long lightingVariant(long reference, int mode) {
        return lightingGrid ? lightingPipelines[mode] : reference;
    }

    private void buildLightingGrid() {
        if (!lightingGrid) {
            return;
        }
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, lightingBuildPipeline);
        vkCmdDispatch(gpu.command, (engine.FogSunTable.CELLS + 63) / 64, 1, 1);
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT);
    }

    private void recordTimings(boolean rebuildFog, java.nio.LongBuffer times) {
        fogBuildNanos =
                rebuildFog ? (long) ((times.get(3) - times.get(0)) * gpu.timestampPeriod) : 0;
        if (rebuildFog) {
            fogBuilds++;
        }
        fogPrimaryNanos =
                fogDownsample == 1
                        ? 0
                        : (long) ((times.get(4) - times.get(5)) * gpu.timestampPeriod);
        lightingBuildNanos =
                lightingGrid ? (long) ((times.get(5) - times.get(3)) * gpu.timestampPeriod) : 0;
        executeNanos = (long) ((times.get(1) - times.get(0)) * gpu.timestampPeriod);
        transferNanos = (long) ((times.get(2) - times.get(1)) * gpu.timestampPeriod);
    }

    private void resolvePrimaryFog() {
        if (fogDownsample == 1) {
            return;
        }
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        vkCmdBindPipeline(
                gpu.command,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                lightingVariant(fogResolvePipeline, 4));
        vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
    }

    private void buildPrimaryFog() {
        if (fogDownsample == 1) {
            return;
        }
        vkCmdBindPipeline(
                gpu.command,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                lightingVariant(fogPrimaryPipeline, 2));
        int columns = (width + fogDownsample - 1) / fogDownsample;
        int rows = (height + fogDownsample - 1) / fogDownsample;
        vkCmdDispatch(gpu.command, (columns + 7) / 8, (rows + 7) / 8, 1);
        gpu.barrier(
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT);
    }

    private void buildFogTable(boolean rebuildFog) {
        if (rebuildFog) {
            vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, fogSunPipeline);
            vkCmdDispatch(gpu.command, (engine.FogSunTable.CELLS + 63) / 64, 1, 1);
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private static boolean sameGeometry(WorldSnapshot before, WorldSnapshot after) {
        if (before.instances().size() != after.instances().size()) {
            return false;
        }
        for (int i = 0; i < before.instances().size(); i++) {
            var a = before.instances().get(i);
            var b = after.instances().get(i);
            if (a.geometry() != b.geometry() || !a.transform().equals(b.transform())) {
                return false;
            }
        }
        return true;
    }

    private void bind() {
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(23, stack);
            var acceleration =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default()
                            .pAccelerationStructures(stack.longs(scene.top.handle));
            writes.get(0)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .pNext(acceleration.address());
            var buffers =
                    new VulkanDevice.Buffer[] {
                        scene.triangles,
                        scene.instances,
                        scene.textures,
                        parameters,
                        mean,
                        output,
                        fogSun,
                        fogPrimary,
                        fogGuides
                    };
            for (int i = 0; i < buffers.length; i++) {
                var info =
                        VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(buffers[i].handle)
                                .offset(0)
                                .range(buffers[i].size);
                writes.get(i + 1)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(i + 1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .pBufferInfo(info);
            }
            var sampled =
                    org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(lightingTexture.sampler)
                            .imageView(lightingTexture.view)
                            .imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL);
            var storage =
                    org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(lightingTexture.view)
                            .imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(10)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(10)
                    .descriptorCount(1)
                    .descriptorType(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(sampled);
            writes.get(11)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(11)
                    .descriptorCount(1)
                    .descriptorType(org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(storage);
            var locations =
                    VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(foveation.locations.handle)
                            .offset(0)
                            .range(foveation.locations.size);
            writes.get(12)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(12)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(locations);
            bindSmoothBuffers(writes, stack);
            var temporalBuffers =
                    new VulkanDevice.Buffer[] {
                        temporal.guide(),
                        temporal.motion,
                        lighting.output,
                        temporal.active ? temporal.presented : output,
                        lighting.albedo,
                        lighting.fallbackMeans,
                        lighting.fallbackQueue,
                        temporal.nrd != null ? temporal.nrd.fresh : output
                    };
            for (int i = 0; i < temporalBuffers.length; i++) {
                var info =
                        VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(temporalBuffers[i].handle)
                                .range(temporalBuffers[i].size);
                writes.get(15 + i)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(15 + i)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(info);
            }
            vkUpdateDescriptorSets(gpu.device, writes, null);
        }
    }

    private void writeParameters(
            WorldSnapshot world, RenderView view, RenderSettings settings, long sample) {
        var camera = view.camera();
        put(0, camera.eye());
        put(1, camera.sensor().origin());
        put(2, camera.sensor().edge1());
        put(3, camera.sensor().edge2());
        // Unused vec4 lanes carry lens radius and axial focus distance in world units.
        parameters.mapped.putFloat(12, camera.aperture()).putFloat(28, camera.focus());
        var sun = world.lights().isEmpty() ? null : (DirectionalLight) world.lights().getFirst();
        put(4, sun == null ? new Vec3(0, 1, 0) : sun.direction());
        put(5, sun == null ? Vec3.ZERO : sun.color().scale(sun.strength()));
        put(6, world.sky().nadir());
        put(7, world.sky().zenith());
        var disc = world.sky().disc();
        put(10, disc == null ? Vec3.ZERO : disc.direction());
        put(11, disc == null ? Vec3.ZERO : disc.radiance());
        parameters.mapped.putFloat(172, disc == null ? 2 : disc.cosineRadius());
        writeAtmosphere(world.sky().atmosphere());
        // Reserved airHeights.w carries the independent transport flag even without atmosphere.
        parameters.mapped.putFloat(220, settings.pathRoulette() ? 1 : 0);
        writeFog(world.sky().fog());
        // fogMeta.z is reserved for the spatial sampling mode; fogMeta.y controls fog jitter.
        parameters.mapped.putFloat(
                (19 + engine.AtmosphereTable.FLOATS / 4) * 16 + 8, samplingMode(settings));
        parameters.mapped.putFloat(
                (19 + engine.AtmosphereTable.FLOATS / 4) * 16 + 12,
                settings.smoothFoveation() ? smoothFoveation.count : foveation.count);
        long seed = cameraSeed(settings.seed(), camera);
        if (temporal.nrd != null) {
            // Scene motion decorrelates noise even with a fixed camera. The key is captured world
            // data, never job order or denoiser history, so captures and revisited views reproduce.
            long sceneSeed = mix(world.revision());
            for (var instance : world.instances()) {
                var transform = instance.transform();
                sceneSeed ^=
                        mix(
                                instance.name().hashCode()
                                        ^ vectorHash(transform.position)
                                        ^ mix(vectorHash(transform.rotation))
                                        ^ mix(vectorHash(transform.scale)));
            }
            seed = mix(seed ^ sceneSeed);
        }
        parameters
                .mapped
                .putInt(128, width)
                .putInt(132, height)
                .putInt(136, settings.pathDepth())
                .putInt(140, (int) seed)
                .putInt(144, (int) (seed >>> 32))
                .putInt(148, Math.toIntExact(sample))
                .putInt(152, fogDownsample)
                .putInt(156, (width + fogDownsample - 1) / fogDownsample);
    }

    private void writeFogLayout() {
        int base = 19 + engine.AtmosphereTable.FLOATS / 4 + 1 + 48;
        put(base, fogLayout == null ? Vec3.ZERO : fogLayout.lower());
        parameters.mapped.putFloat(base * 16 + 12, fogLayout == null ? 0 : engine.FogSunTable.SIZE);
        put(base + 1, fogLayout == null ? new Vec3(1, 1, 1) : fogLayout.extent());
        parameters.mapped.putFloat((base + 1) * 16 + 12, lightingGrid ? 1 : 0);
    }

    private void writeFog(java.util.List<engine.FogRegion> regions) {
        int base = 19 + engine.AtmosphereTable.FLOATS / 4;
        parameters.mapped.putFloat(base * 16, regions.size());
        for (int index = 0; index < regions.size(); index++) {
            var r = regions.get(index);
            int b = base + 1 + index * 6;
            put(b, r.center());
            parameters.mapped.putFloat(b * 16 + 12, r.density());
            put(b + 1, r.halfExtent());
            parameters.mapped.putFloat(
                    (b + 1) * 16 + 12, (float) Math.cos(Math.toRadians(r.yawDegrees())));
            put(b + 2, r.scattering());
            parameters.mapped.putFloat(
                    (b + 2) * 16 + 12, (float) Math.sin(Math.toRadians(r.yawDegrees())));
            put(b + 3, r.absorption());
            put(b + 4, new Vec3(r.anisotropy(), r.heightFalloff(), r.variation()));
            put(b + 5, new Vec3(r.frequency(), r.feather(), 0));
        }
    }

    private void writeAtmosphere(engine.Atmosphere air) {
        parameters.mapped.putFloat(192, 0);
        if (air != null) {
            var p = air.parameters();
            put(12, new Vec3(p.metersPerUnit(), p.groundY(), p.radiusKm()));
            parameters.mapped.putFloat(204, p.heightKm());
            put(13, new Vec3(p.molecularHeightKm(), p.aerosolHeightKm(), p.anisotropy()));
            put(14, p.molecularScattering());
            put(15, p.aerosolScattering());
            put(16, p.absorption());
            put(17, air.solarDirection());
            put(18, air.solarIrradiance());
            var table =
                    uploadedAtmosphere != null && uploadedAtmosphere.matches(p)
                            ? uploadedAtmosphere
                            : engine.AtmosphereTable.get(p);
            if (uploadedAtmosphere != table) {
                for (int index = 0; index < engine.AtmosphereTable.FLOATS; index++) {
                    parameters.mapped.putFloat(304 + index * 4, table.column(index));
                }
                uploadedAtmosphere = table;
            }
        }
    }

    private void put(int index, Vec3 v) {
        parameters
                .mapped
                .putFloat(index * 16, v.x())
                .putFloat(index * 16 + 4, v.y())
                .putFloat(index * 16 + 8, v.z());
    }

    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return n ^ (n >>> 31);
    }

    private static long vectorHash(Vec3 v) {
        long value = mix(Integer.toUnsignedLong(Float.floatToIntBits(v.x())));
        value = mix(value ^ Integer.toUnsignedLong(Float.floatToIntBits(v.y())));
        return mix(value ^ Integer.toUnsignedLong(Float.floatToIntBits(v.z())));
    }

    private static long cameraSeed(long seed, Camera camera) {
        long value = vectorHash(camera.eye());
        value = mix(value ^ vectorHash(camera.sensor().origin()));
        value = mix(value ^ vectorHash(camera.sensor().edge1()));
        value = seed ^ mix(value ^ vectorHash(camera.sensor().edge2()));
        if (camera.aperture() == 0) {
            return value;
        }
        // Match DirectRgbTracer's effective LENS identity, independent of shortcut label.
        value = mix(value ^ Camera.Mode.LENS.ordinal());
        value = mix(value ^ Float.floatToIntBits(camera.focus()));
        return mix(value ^ Float.floatToIntBits(camera.aperture()));
    }

    private void closeImages() {
        if (mean != null) {
            mean.close();
            mean = null;
        }
        if (output != null) {
            output.close();
            output = null;
        }
        if (readback != null) {
            readback.close();
            readback = null;
        }
    }

    private void destroyPipeline(long handle) {
        if (handle != 0) {
            vkDestroyPipeline(gpu.device, handle, null);
        }
    }

    private void destroyPipelines() {
        destroyPipeline(pipeline);
        for (long variant : reconstructionPipelines) {
            destroyPipeline(variant);
        }
        for (long variant : visibilityPipelines) {
            destroyPipeline(variant);
        }
        for (long variant : fallbackPipelines) {
            destroyPipeline(variant);
        }
        destroyPipeline(queuePipeline);
        for (long variant : temporalPipelines) {
            destroyPipeline(variant);
        }
        destroyPipeline(fogSunPipeline);
        destroyPipeline(fogPrimaryPipeline);
        destroyPipeline(fogReconstructPipeline);
        destroyPipeline(fogResolvePipeline);
        destroyPipeline(lightingBuildPipeline);
        destroyPipeline(smoothResolvePipeline);
        for (long variant : foveatedPipelines) {
            destroyPipeline(variant);
        }
        for (long variant : lightingPipelines) {
            destroyPipeline(variant);
        }
    }

    @Override
    public void close() {
        if (gpu.device != null) {
            vkDeviceWaitIdle(gpu.device);
            if (descriptorPool != 0) {
                vkDestroyDescriptorPool(gpu.device, descriptorPool, null);
            }
            destroyPipelines();
            if (pipelineLayout != 0) {
                vkDestroyPipelineLayout(gpu.device, pipelineLayout, null);
            }
            if (descriptorLayout != 0) {
                vkDestroyDescriptorSetLayout(gpu.device, descriptorLayout, null);
            }
            if (scene != null) {
                scene.close();
            }
        }
        if (display != null) {
            display.close();
            display = null;
        }
        if (temporal != null) {
            temporal.close();
        }
        if (lighting != null) {
            lighting.close();
        }
        gpu.close();
    }
}
