package gpu;

import static engine.RenderAvailability.State.AVAILABLE;
import static engine.RenderAvailability.State.ERROR;
import static engine.RenderAvailability.State.UNAVAILABLE;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;

import engine.RenderAvailability;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Explicit window-free hardware check; does not compile scenes or shaders. */
public final class GpuProbe {
    private GpuProbe() {}

    public static RenderAvailability probe() {
        try (var device = new VulkanDevice();
                var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.physical, properties);
            return new RenderAvailability(
                    AVAILABLE,
                    device.name
                            + "; vendor="
                            + properties.vendorID()
                            + "; driverVersion="
                            + Integer.toUnsignedString(properties.driverVersion())
                            + "; apiVersion="
                            + Integer.toUnsignedString(properties.apiVersion()));
        } catch (UnsupportedOperationException | UnsatisfiedLinkError failure) {
            return new RenderAvailability(UNAVAILABLE, failure.toString());
        } catch (RuntimeException | LinkageError failure) {
            return new RenderAvailability(ERROR, failure.toString());
        }
    }
}
