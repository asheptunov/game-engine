package gpu;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import engine.PolygonMesh;
import engine.Texture2D;
import engine.WorldSnapshot;

import math.Vec3;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Geometry and textures are compiled once per changed immutable world content. */
final class GpuScene implements AutoCloseable {
    private final VulkanDevice gpu;
    private final Map<PolygonMesh, MeshRange> meshes = new LinkedHashMap<>();
    private final List<Structure> structures = new ArrayList<>();
    private final List<VulkanDevice.Buffer> buffers = new ArrayList<>();
    final VulkanDevice.Buffer triangles;
    VulkanDevice.Buffer instances;
    VulkanDevice.Buffer textures;
    Structure top;
    final long buildNanos;
    private final boolean reuseTopStorage = !Boolean.getBoolean("game.rebuildGpuTlas");
    private final boolean separateSubmission =
            Boolean.getBoolean("game.separateGpuSceneSubmission");
    private boolean pendingTopBuild;
    private VulkanDevice.Buffer topInstances;
    private VulkanDevice.Buffer topScratch;
    private long topScratchAddress;
    private int topCount;
    long topReuses;
    long topAllocations;

    record MeshRange(Structure structure, int firstTriangle) {}

    GpuScene(VulkanDevice gpu, WorldSnapshot world) {
        this.gpu = gpu;
        long start = System.nanoTime();
        try {
            var positions = new ArrayList<Float>();
            compileGeometry(world, meshes, positions);
            triangles = floats(positions, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            compileMaterials(world, meshes);
            top = compileInstances(world, meshes);
            buildNanos = System.nanoTime() - start;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void compileGeometry(
            WorldSnapshot world, Map<PolygonMesh, MeshRange> meshes, List<Float> positions) {
        for (var object : world.instances()) {
            var mesh = (PolygonMesh) object.geometry();
            if (meshes.containsKey(mesh)) {
                continue;
            }
            int first = positions.size() / 12;
            int faceIndex = 0;
            for (var face : mesh.faces()) {
                var ids = face.vertexIds();
                for (int i = 1; i + 1 < ids.size(); i++) {
                    append(positions, mesh.requireVertex(ids.get(0)).position(), faceIndex);
                    append(positions, mesh.requireVertex(ids.get(i)).position(), 0);
                    append(positions, mesh.requireVertex(ids.get(i + 1)).position(), 0);
                }
                faceIndex++;
            }
            int count = positions.size() / 12 - first;
            var vertices =
                    floats(
                            positions.subList(first * 12, positions.size()),
                            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            try (var stack = MemoryStack.stackPush()) {
                var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
                geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
                geometry.get(0)
                        .geometry()
                        .triangles()
                        .sType$Default()
                        .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                        .vertexStride(16)
                        .maxVertex(count * 3 - 1)
                        .indexType(VK_INDEX_TYPE_NONE_KHR)
                        .vertexData()
                        .deviceAddress(vertices.address());
                meshes.put(
                        mesh,
                        new MeshRange(
                                build(
                                        geometry,
                                        count,
                                        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR),
                                first));
            }
            vertices.close();
        }
    }

    private void compileMaterials(WorldSnapshot world, Map<PolygonMesh, MeshRange> meshes) {
        var texels = new ArrayList<Float>();
        var textureOffsets = new HashMap<Texture2D, Integer>();
        var materials = new ArrayList<Float>();
        for (var object : world.instances()) {
            append(
                    materials,
                    object.material().color(),
                    meshes.get(object.geometry()).firstTriangle());
            var leaf = object.material().leaf();
            var relay = object.material().viewRelay();
            if (relay != null) {
                append(materials, new Vec3(-1, 0, 0), -3);
                append(materials, new Vec3(relay.magnification(), relay.exitDistance(), 0), 0);
                append(materials, Vec3.ZERO, 0);
                continue;
            }
            if (leaf != null) {
                append(materials, new Vec3(-1, 0, 0), -2);
                append(materials, leaf.tint(), leaf.thicknessMm());
                append(
                        materials,
                        new Vec3(leaf.transmission(), leaf.forwardFraction(), leaf.exponent()),
                        0);
                continue;
            }
            var binding = object.material().textures();
            for (Texture2D texture :
                    binding == null
                            ? new Texture2D[3]
                            : new Texture2D[] {binding.top(), binding.bottom(), binding.sides()}) {
                if (texture == null) {
                    append(materials, new Vec3(-1, 0, 0), surfaceTag(object.material()));
                    continue;
                }
                int offset =
                        textureOffsets.computeIfAbsent(texture, key -> appendTexture(texels, key));
                append(materials, new Vec3(offset, texture.width(), texture.height()), 0);
            }
        }
        instances = floats(materials, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        textures = floats(texels, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    /** Shader material tag: positive IOR for glass, -1 for thin leaves, zero for opaque diffuse. */
    private static float surfaceTag(engine.Material material) {
        if (material.clearGlass()) {
            return material.ior();
        }
        return material.kind() == engine.Material.Kind.THIN_LEAF ? -1 : 0;
    }

    private static int appendTexture(List<Float> texels, Texture2D texture) {
        int index = texels.size() / 4;
        var pixel = new float[3];
        for (int y = 0; y < texture.height(); y++) {
            for (int x = 0; x < texture.width(); x++) {
                texture.sample((x + .5f) / texture.width(), (y + .5f) / texture.height(), pixel);
                append(texels, new Vec3(pixel[0], pixel[1], pixel[2]), 0);
            }
        }
        return index;
    }

    private Structure compileInstances(WorldSnapshot world, Map<PolygonMesh, MeshRange> meshes) {
        Structure result;
        var data = instanceBuffer(world.instances().size());
        try (var stack = MemoryStack.stackPush()) {
            var records = new VkAccelerationStructureInstanceKHR.Buffer(data.mapped);
            int index = 0;
            for (var object : world.instances()) {
                var record = records.get(index);
                var transform = object.transform();
                var x = transform.vector(new Vec3(1, 0, 0));
                var y = transform.vector(new Vec3(0, 1, 0));
                var z = transform.vector(new Vec3(0, 0, 1));
                var p = transform.position;
                record.transform()
                        .matrix()
                        .put(
                                new float[] {
                                    x.x(), y.x(), z.x(), p.x(), x.y(), y.y(), z.y(), p.y(), x.z(),
                                    y.z(), z.z(), p.z()
                                });
                record.instanceCustomIndex(index++)
                        .mask(255)
                        .instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(
                                meshes.get(object.geometry()).structure().address());
            }
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0)
                    .geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false)
                    .data()
                    .deviceAddress(data.address());
            result = buildTop(geometry, world.instances().size());
        }
        if (!reuseTopStorage) {
            data.close();
        }
        return result;
    }

    private VulkanDevice.Buffer instanceBuffer(int count) {
        long bytes = 64L * count;
        if (reuseTopStorage && topInstances != null && topInstances.size == bytes) {
            return topInstances;
        }
        if (topInstances != null) {
            topInstances.close();
        }
        var data =
                own(
                        gpu.buffer(
                                bytes,
                                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                true));
        if (reuseTopStorage) {
            topInstances = data;
        }
        return data;
    }

    /**
     * Rebuild in existing storage, rather than refitting a hierarchy that can degrade after motion.
     */
    private Structure buildTop(VkAccelerationStructureGeometryKHR.Buffer geometry, int count) {
        if (reuseTopStorage && top != null && topCount == count) {
            topReuses++;
            if (!separateSubmission) {
                // Retained inputs and scratch outlive recording. The GPU owner records this build
                // before the next trace, removing a submission and its CPU fence round trip.
                pendingTopBuild = true;
                return top;
            }
            try (var stack = MemoryStack.stackPush()) {
                var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                build.get(0)
                        .sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .pGeometries(geometry)
                        .geometryCount(1)
                        .dstAccelerationStructure(top.handle)
                        .scratchData()
                        .deviceAddress(topScratchAddress);
                executeBuild(build, count, stack);
                return top;
            }
        }
        var result = build(geometry, count, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        pendingTopBuild = false;
        topCount = count;
        topAllocations++;
        return result;
    }

    /** Record a retained TLAS rebuild into the owner's already-open tracing command buffer. */
    boolean recordPendingBuild() {
        if (!pendingTopBuild) {
            return false;
        }
        try (var stack = MemoryStack.stackPush()) {
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0)
                    .geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false)
                    .data()
                    .deviceAddress(topInstances.address());
            var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0)
                    .sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry)
                    .geometryCount(1)
                    .dstAccelerationStructure(top.handle)
                    .scratchData()
                    .deviceAddress(topScratchAddress);
            recordBuild(build, topCount, stack);
        }
        pendingTopBuild = false;
        return true;
    }

    private static void append(List<Float> list, Vec3 xyz, float w) {
        list.add(xyz.x());
        list.add(xyz.y());
        list.add(xyz.z());
        list.add(w);
    }

    private VulkanDevice.Buffer own(VulkanDevice.Buffer buffer) {
        buffers.removeIf(old -> old.handle == 0);
        buffers.add(buffer);
        return buffer;
    }

    private VulkanDevice.Buffer floats(List<Float> values, int usage) {
        var result =
                own(
                        gpu.buffer(
                                values.size() * 4L,
                                usage | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                false));
        try (var staging =
                        gpu.buffer(
                                result.size,
                                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                true);
                var stack = MemoryStack.stackPush()) {
            for (int i = 0; i < values.size(); i++) {
                staging.mapped.putFloat(i * 4, values.get(i));
            }
            gpu.begin();
            var copy = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack).size(result.size);
            org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(gpu.command, staging.handle, result.handle, copy);
            gpu.barrier(
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                            | org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT);
            gpu.submit();
        }
        return result;
    }

    void updateMaterials(WorldSnapshot world) {
        instances.close();
        textures.close();
        compileMaterials(world, meshes);
    }

    /** Mesh buffers and bottom-level structures remain valid across instance motion/removal. */
    boolean containsGeometry(WorldSnapshot world) {
        return world.instances().stream().allMatch(object -> meshes.containsKey(object.geometry()));
    }

    void updateInstances(WorldSnapshot before, WorldSnapshot after) {
        if (!sameMaterialLayout(before, after)) {
            updateMaterials(after);
        }
        var previous = top;
        top = compileInstances(after, meshes);
        // VulkanDevice.submit waits for completion; no traversal can still use the old top level.
        if (previous != top) {
            vkDestroyAccelerationStructureKHR(gpu.device, previous.handle, null);
            previous.storage.close();
            structures.remove(previous);
        }
    }

    private static boolean sameMaterialLayout(WorldSnapshot before, WorldSnapshot after) {
        if (before.instances().size() != after.instances().size()) {
            return false;
        }
        for (int index = 0; index < before.instances().size(); index++) {
            var a = before.instances().get(index);
            var b = after.instances().get(index);
            if (a.geometry() != b.geometry() || !a.material().equals(b.material())) {
                return false;
            }
        }
        return true;
    }

    private Structure build(
            VkAccelerationStructureGeometryKHR.Buffer geometry, int count, int type) {
        try (var stack = MemoryStack.stackPush()) {
            var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.get(0)
                    .sType$Default()
                    .type(type)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry)
                    .geometryCount(1);
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    gpu.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0),
                    stack.ints(count),
                    sizes);
            var storage =
                    own(
                            gpu.buffer(
                                    sizes.accelerationStructureSize(),
                                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                    false));
            var out = stack.longs(0);
            VulkanDevice.check(
                    vkCreateAccelerationStructureKHR(
                            gpu.device,
                            VkAccelerationStructureCreateInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .buffer(storage.handle)
                                    .size(storage.size)
                                    .type(type),
                            null,
                            out),
                    "acceleration structure");
            var result = new Structure(out.get(0), storage);
            structures.add(result);
            // Overallocate and align to the queried device requirement.
            var properties =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                            .sType$Default();
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(
                    gpu.physical,
                    VkPhysicalDeviceProperties2.calloc(stack)
                            .sType$Default()
                            .pNext(properties.address()));
            long alignment = properties.minAccelerationStructureScratchOffsetAlignment();
            var scratch =
                    own(
                            gpu.buffer(
                                    sizes.buildScratchSize() + alignment,
                                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                    false));
            build.get(0)
                    .dstAccelerationStructure(result.handle)
                    .scratchData()
                    .deviceAddress((scratch.address() + alignment - 1) & -alignment);
            executeBuild(build, count, stack);
            if (type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR && reuseTopStorage) {
                if (topScratch != null) {
                    topScratch.close();
                }
                topScratch = scratch;
                topScratchAddress = (scratch.address() + alignment - 1) & -alignment;
            } else {
                scratch.close();
            }
            return result;
        }
    }

    private void executeBuild(
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build,
            int count,
            MemoryStack stack) {
        gpu.begin();
        recordBuild(build, count, stack);
        gpu.submit();
    }

    private void recordBuild(
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build,
            int count,
            MemoryStack stack) {
        var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(count);
        // The owner waits after every submission. This dependency also makes previous traversal
        // and scratch accesses visible before overwriting the retained top-level storage.
        gpu.barrier(
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        vkCmdBuildAccelerationStructuresKHR(gpu.command, build, stack.pointers(range.address()));
        gpu.barrier(
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    final class Structure {
        final long handle;
        final VulkanDevice.Buffer storage;

        Structure(long handle, VulkanDevice.Buffer storage) {
            this.handle = handle;
            this.storage = storage;
        }

        long address() {
            try (var stack = MemoryStack.stackPush()) {
                return vkGetAccelerationStructureDeviceAddressKHR(
                        gpu.device,
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                                .sType$Default()
                                .accelerationStructure(handle));
            }
        }
    }

    @Override
    public void close() {
        for (var structure : structures.reversed()) {
            vkDestroyAccelerationStructureKHR(gpu.device, structure.handle, null);
        }
        structures.clear();
        for (var buffer : buffers.reversed()) {
            buffer.close();
        }
        buffers.clear();
    }
}
