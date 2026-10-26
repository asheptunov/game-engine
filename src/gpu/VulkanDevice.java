package gpu;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Single-owner Vulkan compute device. All commands finish before resources are retired. */
final class VulkanDevice implements AutoCloseable {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    VkCommandBuffer command;
    long pool;
    long fence;
    long queries;
    String name;
    String description = "unavailable";
    float timestampPeriod;
    long allocatedBytes;
    long peakBytes;
    final List<AutoCloseable> resources = new ArrayList<>();

    VulkanDevice() {
        org.lwjgl.system.Configuration.STACK_SIZE.set(512);
        try (var stack = MemoryStack.stackPush()) {
            var app =
                    VkApplicationInfo.calloc(stack)
                            .sType$Default()
                            .pApplicationName(stack.UTF8("RayTracingEngine S1.1"))
                            .apiVersion(VK_API_VERSION_1_2);
            var ci = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            var pointer = stack.mallocPointer(1);
            check(vkCreateInstance(ci, null, pointer), "create instance");
            instance = new VkInstance(pointer.get(0), ci);
            int family = selectPhysicalDevice(stack);
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            name = properties.deviceNameString();
            description =
                    name
                            + "; vendor="
                            + properties.vendorID()
                            + "; driverVersion="
                            + Integer.toUnsignedString(properties.driverVersion())
                            + "; apiVersion="
                            + Integer.toUnsignedString(properties.apiVersion());
            timestampPeriod = properties.limits().timestampPeriod();
            var ray =
                    VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .rayQuery(true);
            var acceleration =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .accelerationStructure(true)
                            .pNext(ray.address());
            var address =
                    VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                            .sType$Default()
                            .bufferDeviceAddress(true)
                            .pNext(acceleration.address());
            var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0)
                    .sType$Default()
                    .queueFamilyIndex(family)
                    .pQueuePriorities(stack.floats(1));
            var create =
                    VkDeviceCreateInfo.calloc(stack)
                            .sType$Default()
                            .pNext(address.address())
                            .pQueueCreateInfos(queues)
                            .pEnabledFeatures(
                                    VkPhysicalDeviceFeatures.calloc(stack)
                                            .shaderFloat64(true)
                                            .shaderInt64(true))
                            .ppEnabledExtensionNames(
                                    stack.pointers(
                                            stack.UTF8(
                                                    VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME),
                                            stack.UTF8(VK_KHR_RAY_QUERY_EXTENSION_NAME),
                                            stack.UTF8(
                                                    VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)));
            check(vkCreateDevice(physical, create, null, pointer), "create device");
            device = new VkDevice(pointer.get(0), physical, create);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            var handle = stack.longs(0);
            check(
                    vkCreateCommandPool(
                            device,
                            VkCommandPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .queueFamilyIndex(family)
                                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                            null,
                            handle),
                    "command pool");
            pool = handle.get(0);
            check(
                    vkAllocateCommandBuffers(
                            device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(pool)
                                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            pointer),
                    "command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);
            check(
                    vkCreateFence(
                            device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "fence");
            fence = handle.get(0);
            check(
                    vkCreateQueryPool(
                            device,
                            VkQueryPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                                    .queryCount(6),
                            null,
                            handle),
                    "timestamps");
            queries = handle.get(0);
        } catch (RuntimeException | Error failure) {
            failure.addSuppressed(
                    new IllegalStateException(
                            "Initialization state: instance="
                                    + (instance != null)
                                    + ", physical="
                                    + (physical != null)
                                    + ", device="
                                    + (device != null)
                                    + ", commandPool="
                                    + pool
                                    + "; "
                                    + description));
            FailedInitialization.retire(failure, this::close);
            throw failure;
        }
    }

    private int selectPhysicalDevice(MemoryStack stack) {
        var count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
        var devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate devices");
        int family = -1;
        for (int i = 0; i < devices.capacity(); i++) {
            var candidate = new VkPhysicalDevice(devices.get(i), instance);
            var ray = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
            var acceleration =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .pNext(ray.address());
            var address =
                    VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                            .sType$Default()
                            .pNext(acceleration.address());
            var features =
                    VkPhysicalDeviceFeatures2.calloc(stack)
                            .sType$Default()
                            .pNext(address.address());
            vkGetPhysicalDeviceFeatures2(candidate, features);
            if (!ray.rayQuery()
                    || !acceleration.accelerationStructure()
                    || !address.bufferDeviceAddress()
                    || !features.features().shaderFloat64()
                    || !features.features().shaderInt64()) {
                continue;
            }
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            for (int j = 0; j < families.capacity(); j++) {
                if ((families.get(j).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0
                        && families.get(j).timestampValidBits() == 64) {
                    family = j;
                    physical = candidate;
                    break;
                }
            }
            if (physical != null) {
                break;
            }
        }
        if (physical == null) {
            throw new UnsupportedOperationException(
                    "GPU needs Vulkan ray queries, float64/int64 and 64-bit compute"
                            + " timestamps");
        }
        return family;
    }

    static void check(int result, String operation) {
        if (result == org.lwjgl.vulkan.VK10.VK_ERROR_INCOMPATIBLE_DRIVER) {
            throw new UnsupportedOperationException("Vulkan driver unavailable for " + operation);
        }
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan " + operation + " failed: " + result);
        }
    }

    Buffer buffer(long size, int usage, boolean host) {
        var result = new Buffer(Math.max(16, size), usage, host);
        resources.add(result);
        return result;
    }

    final class Buffer implements AutoCloseable {
        final long size;
        long handle;
        long memory;
        long bytes;
        ByteBuffer mapped;

        Buffer(long size, int usage, boolean host) {
            this.size = size;
            try (var stack = MemoryStack.stackPush()) {
                var out = stack.longs(0);
                check(
                        vkCreateBuffer(
                                device,
                                VkBufferCreateInfo.calloc(stack)
                                        .sType$Default()
                                        .size(size)
                                        .usage(usage)
                                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                                null,
                                out),
                        "buffer");
                handle = out.get(0);
                var requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, handle, requirements);
                var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, properties);
                int flags =
                        host
                                ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                        | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                                : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
                int type = memoryType(properties, requirements.memoryTypeBits(), flags, host);
                var address =
                        VkMemoryAllocateFlagsInfo.calloc(stack)
                                .sType$Default()
                                .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                var allocate =
                        VkMemoryAllocateInfo.calloc(stack)
                                .sType$Default()
                                .allocationSize(requirements.size())
                                .memoryTypeIndex(type);
                if ((usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                    allocate.pNext(address.address());
                }
                check(vkAllocateMemory(device, allocate, null, out), "allocate memory");
                memory = out.get(0);
                bytes = requirements.size();
                allocatedBytes += bytes;
                peakBytes = Math.max(peakBytes, allocatedBytes);
                check(vkBindBufferMemory(device, handle, memory, 0), "bind memory");
                if (host) {
                    var pointer = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, size, 0, pointer), "map memory");
                    mapped = MemoryUtil.memByteBuffer(pointer.get(0), Math.toIntExact(size));
                }
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        long address() {
            try (var stack = MemoryStack.stackPush()) {
                return vkGetBufferDeviceAddress(
                        device,
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
            }
        }

        @Override
        public void close() {
            if (mapped != null) {
                vkUnmapMemory(device, memory);
                mapped = null;
            }
            if (handle != 0) {
                vkDestroyBuffer(device, handle, null);
                handle = 0;
            }
            if (memory != 0) {
                vkFreeMemory(device, memory, null);
                memory = 0;
                allocatedBytes -= bytes;
            }
            resources.remove(this);
        }
    }

    // Coherence does not imply CPU caching. Reading each float from uncached staging can dominate
    // the entire render even when the GPU copy takes less than a millisecond.
    private static int memoryType(
            VkPhysicalDeviceMemoryProperties properties, int allowed, int flags, boolean host) {
        int fallback = -1;
        for (int i = 0; i < properties.memoryTypeCount(); i++) {
            int candidate = properties.memoryTypes(i).propertyFlags();
            if ((allowed & (1 << i)) == 0 || (candidate & flags) != flags) {
                continue;
            }
            if (!host
                    || (candidate & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                            != 0) {
                return i;
            }
            fallback = i;
        }
        if (fallback < 0) {
            throw new IllegalStateException("GPU memory type unavailable");
        }
        return fallback;
    }

    void begin() {
        check(vkResetCommandBuffer(command, 0), "reset command");
        try (var stack = MemoryStack.stackPush()) {
            check(
                    vkBeginCommandBuffer(
                            command,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin command");
        }
    }

    void submit() {
        check(vkEndCommandBuffer(command), "end command");
        try (var stack = MemoryStack.stackPush()) {
            check(vkResetFences(device, fence), "reset fence");
            var submit =
                    VkSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pCommandBuffers(stack.pointers(command));
            check(vkQueueSubmit(queue, submit, fence), "submit");
            // Only the GPU owner waits. Input and display never call this method.
            check(vkWaitForFences(device, fence, true, 30_000_000_000L), "wait for completion");
        }
    }

    void barrier(int sourceStage, int sourceAccess, int destinationStage, int destinationAccess) {
        try (var stack = MemoryStack.stackPush()) {
            var barriers = VkMemoryBarrier.calloc(1, stack);
            barriers.get(0)
                    .sType$Default()
                    .srcAccessMask(sourceAccess)
                    .dstAccessMask(destinationAccess);
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, barriers, null, null);
        }
    }

    @Override
    public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            for (var resource : List.copyOf(resources).reversed()) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    /* Continue retiring device resources. */
                }
            }
            if (queries != 0) {
                vkDestroyQueryPool(device, queries, null);
            }
            if (fence != 0) {
                vkDestroyFence(device, fence, null);
            }
            if (pool != 0) {
                vkDestroyCommandPool(device, pool, null);
            }
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }
}
