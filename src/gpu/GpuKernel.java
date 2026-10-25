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
import engine.Material;
import engine.PolygonMesh;
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
    GpuScene scene;
    private WorldSnapshot compiledWorld;
    private long descriptorLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private VulkanDevice.Buffer parameters;
    private VulkanDevice.Buffer mean;
    private VulkanDevice.Buffer output;
    private VulkanDevice.Buffer readback;
    private int width;
    private int height;
    long executeNanos;
    long transferNanos;
    long hostNanos;
    long prepareNanos;
    long maximumPrepareNanos;

    GpuKernel() {
        this(() -> {});
    }

    GpuKernel(Runnable beforeDispatch) {
        long started = System.nanoTime();
        this.beforeDispatch = beforeDispatch;
        gpu = new VulkanDevice();
        try {
            parameters = gpu.buffer(192, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            createPipeline();
            initializationNanos = System.nanoTime() - started;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static void validate(WorldSnapshot world, RenderView view, RenderSettings settings) {
        if (view.camera().projection() != Camera.Projection.PERSPECTIVE) {
            throw new IllegalArgumentException("GPU supports perspective cameras only");
        }
        if (!settings.acceleration()
                || settings.pathDepth() > 2
                || settings.temporal()
                || settings.temporalBudget()
                || settings.interactive()) {
            throw new IllegalArgumentException(
                    "GPU requires acceleration, depth 0..2, fixed grids and temporal off");
        }
        if (world.instances().isEmpty() || !world.legacyObjects().isEmpty()) {
            throw new IllegalArgumentException(
                    "GPU needs nonempty polygon instances and no legacy objects");
        }
        if (world.lights().size() > 1
                || world.lights().stream()
                        .anyMatch(light -> !(light instanceof DirectionalLight))) {
            throw new IllegalArgumentException(
                    "GPU supports one directional sun and sky; point/area lights are unsupported");
        }
        for (var object : world.instances()) {
            var m = object.material();
            if (!(object.geometry() instanceof PolygonMesh)
                    || m.kind() != Material.Kind.DIFFUSE
                    || m.roughness() != 0
                    || m.emissive()
                    || m.scattering() != 0) {
                throw new IllegalArgumentException(
                        "GPU supports opaque diffuse polygon instances: " + object.name());
            }
        }
    }

    private void createPipeline() {
        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(7, stack);
            for (int i = 0; i < 7; i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorCount(1)
                        .descriptorType(
                                i == 0
                                        ? VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
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
                                    .pSetLayouts(stack.longs(descriptorLayout)),
                            null,
                            handle),
                    "pipeline layout");
            pipelineLayout = handle.get(0);
            byte[] source;
            try {
                source = Files.readAllBytes(Path.of("out/gpu/diffuse.spv"));
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
                        "compute pipeline");
                pipeline = handle.get(0);
            } finally {
                vkDestroyShaderModule(gpu.device, shader, null);
            }
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(6);
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

    void trace(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            long sample,
            float[] pixels) {
        validate(world, view, settings);
        long start = System.nanoTime();
        boolean changed = false;
        if (compiledWorld == null || !compiledWorld.instances().equals(world.instances())) {
            if (compiledWorld != null && sameGeometry(compiledWorld, world)) {
                scene.updateMaterials(world);
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
            readback = gpu.buffer(output.size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            changed = true;
        }
        if (changed) {
            bind();
        }
        prepareNanos = System.nanoTime() - start;
        maximumPrepareNanos = Math.max(maximumPrepareNanos, prepareNanos);
        writeParameters(world, view, settings, sample);
        beforeDispatch.run();
        gpu.begin();
        vkCmdResetQueryPool(gpu.command, gpu.queries, 0, 3);
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
            vkCmdDispatch(gpu.command, (width + 7) / 8, (height + 7) / 8, 1);
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, gpu.queries, 1);
            var copy = VkBufferCopy.calloc(1, stack).size(output.size);
            vkCmdCopyBuffer(gpu.command, output.handle, readback.handle, copy);
            gpu.barrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_ACCESS_HOST_READ_BIT);
            vkCmdWriteTimestamp(gpu.command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, gpu.queries, 2);
            gpu.submit();
            var times = stack.mallocLong(3);
            VulkanDevice.check(
                    vkGetQueryPoolResults(
                            gpu.device,
                            gpu.queries,
                            0,
                            3,
                            times,
                            8,
                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                    "read timestamps");
            executeNanos = (long) ((times.get(1) - times.get(0)) * gpu.timestampPeriod);
            transferNanos = (long) ((times.get(2) - times.get(1)) * gpu.timestampPeriod);
        }
        for (int i = 0; i < width * height; i++) {
            pixels[i * 3] = readback.mapped.getFloat(i * 16);
            pixels[i * 3 + 1] = readback.mapped.getFloat(i * 16 + 4);
            pixels[i * 3 + 2] = readback.mapped.getFloat(i * 16 + 8);
        }
        hostNanos = System.nanoTime() - start;
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
            var writes = VkWriteDescriptorSet.calloc(7, stack);
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
                        scene.triangles, scene.instances, scene.textures, parameters, mean, output
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
        long seed = cameraSeed(settings.seed(), camera);
        parameters
                .mapped
                .putInt(128, width)
                .putInt(132, height)
                .putInt(136, settings.pathDepth())
                .putInt(140, (int) seed)
                .putInt(144, (int) (seed >>> 32))
                .putInt(148, Math.toIntExact(sample));
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

    @Override
    public void close() {
        if (gpu.device != null) {
            vkDeviceWaitIdle(gpu.device);
            if (descriptorPool != 0) {
                vkDestroyDescriptorPool(gpu.device, descriptorPool, null);
            }
            if (pipeline != 0) {
                vkDestroyPipeline(gpu.device, pipeline, null);
            }
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
        gpu.close();
    }
}
