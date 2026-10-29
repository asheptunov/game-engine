package gpu;

import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.KHRWin32Surface.vkCreateWin32SurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainPresentFenceInfoEXT;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;

/**
 * GPU-owner-only Windows presentation. Present fences protect semaphore and swapchain retirement.
 */
final class GpuSwapchain implements AutoCloseable {
    private final VulkanDevice gpu;
    private long surface;
    private long swapchain;
    private long acquireFence;
    private long[] images = new long[0];
    private long[] ready = new long[0];
    private long[] presented = new long[0];
    private boolean[] pending = new boolean[0];
    private GpuCompositor compositor;
    private int width;
    private int height;
    private boolean rebuild;

    GpuSwapchain(VulkanDevice gpu, long hwnd, long hinstance) {
        this.gpu = gpu;
        if (!gpu.presentation) {
            throw new UnsupportedOperationException(
                    "Direct presentation needs Windows Vulkan swapchain maintenance1; use"
                            + " -Dgame.swingPresentation=true");
        }
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.longs(0);
            VulkanDevice.check(
                    vkCreateWin32SurfaceKHR(
                            gpu.instance,
                            VkWin32SurfaceCreateInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .hwnd(hwnd)
                                    .hinstance(hinstance),
                            null,
                            handle),
                    "create window surface");
            surface = handle.get(0);
            var support = stack.ints(0);
            VulkanDevice.check(
                    vkGetPhysicalDeviceSurfaceSupportKHR(
                            gpu.physical, gpu.queueFamily, surface, support),
                    "surface support");
            if (support.get(0) == 0) {
                throw new UnsupportedOperationException("GPU queue cannot present to this window");
            }
            VulkanDevice.check(
                    vkCreateFence(
                            gpu.device,
                            VkFenceCreateInfo.calloc(stack).sType$Default(),
                            null,
                            handle),
                    "acquire fence");
            acquireFence = handle.get(0);
            compositor = new GpuCompositor(gpu);
            gpu.resources.add(this);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    boolean present(
            GpuDevicePixels frame,
            float exposure,
            int requestedWidth,
            int requestedHeight,
            int[] overlay) {
        return present(frame, exposure, requestedWidth, requestedHeight, overlay, engine.Bloom.OFF);
    }

    boolean present(
            GpuDevicePixels frame,
            float exposure,
            int requestedWidth,
            int requestedHeight,
            int[] overlay,
            engine.Bloom bloom) {
        if (requestedWidth <= 0 || requestedHeight <= 0 || surface == 0) {
            return false;
        }
        try (var stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            int status = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu.physical, surface, caps);
            if (status == VK_ERROR_SURFACE_LOST_KHR) {
                return false;
            }
            VulkanDevice.check(status, "surface capabilities");
            int w =
                    caps.currentExtent().width() == -1
                            ? Math.clamp(
                                    requestedWidth,
                                    caps.minImageExtent().width(),
                                    caps.maxImageExtent().width())
                            : caps.currentExtent().width();
            int h =
                    caps.currentExtent().height() == -1
                            ? Math.clamp(
                                    requestedHeight,
                                    caps.minImageExtent().height(),
                                    caps.maxImageExtent().height())
                            : caps.currentExtent().height();
            if (w == 0 || h == 0) {
                return false;
            }
            if (w > 8192 || h > 8192) {
                throw new IllegalArgumentException("Presentation extent exceeds 8192 pixels");
            }
            if (swapchain == 0 || rebuild || width != w || height != h) {
                create(caps, w, h, stack);
            }
            var index = stack.ints(0);
            if (!acquire(index)) {
                return false;
            }
            int slot = index.get(0);
            waitPresented(slot);
            gpu.begin();
            compositor.encode(
                    frame.snapshot.source,
                    frame.width(),
                    frame.height(),
                    exposure,
                    width,
                    height,
                    overlay,
                    bloom);
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            transition(
                    images[slot],
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack);
            var region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(
                    gpu.command,
                    compositor.output().handle,
                    images[slot],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);
            transition(
                    images[slot],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    stack);
            gpu.submit(ready[slot]);
            VulkanDevice.check(vkResetFences(gpu.device, presented[slot]), "reset present fence");
            var completion =
                    VkSwapchainPresentFenceInfoEXT.calloc(stack)
                            .sType$Default()
                            .pFences(stack.longs(presented[slot]));
            var info =
                    VkPresentInfoKHR.calloc(stack)
                            .sType$Default()
                            .pNext(completion.address())
                            .pWaitSemaphores(stack.longs(ready[slot]))
                            .swapchainCount(1)
                            .pSwapchains(stack.longs(swapchain))
                            .pImageIndices(index);
            status = vkQueuePresentKHR(gpu.queue, info);
            pending[slot] = true;
            if (status == VK_ERROR_OUT_OF_DATE_KHR
                    || status == VK_SUBOPTIMAL_KHR
                    || status == VK_ERROR_SURFACE_LOST_KHR) {
                rebuild = true;
                return status == VK_SUBOPTIMAL_KHR;
            }
            VulkanDevice.check(status, "present image");
            return true;
        }
    }

    private boolean acquire(java.nio.IntBuffer index) {
        VulkanDevice.check(vkResetFences(gpu.device, acquireFence), "reset acquire fence");
        int status = vkAcquireNextImageKHR(gpu.device, swapchain, 0, 0, acquireFence, index);
        if (status == VK_NOT_READY || status == VK_TIMEOUT) {
            return false;
        }
        if (status == VK_ERROR_OUT_OF_DATE_KHR || status == VK_ERROR_SURFACE_LOST_KHR) {
            rebuild = true;
            return false;
        }
        if (status == VK_SUBOPTIMAL_KHR) {
            rebuild = true;
        } else {
            VulkanDevice.check(status, "acquire image");
        }
        VulkanDevice.check(
                vkWaitForFences(gpu.device, acquireFence, true, 30_000_000_000L),
                "acquire completion");
        return true;
    }

    private void create(VkSurfaceCapabilitiesKHR caps, int w, int h, MemoryStack stack) {
        retireSwapchain();
        if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
            throw new UnsupportedOperationException(
                    "Window images do not support GPU transfer presentation");
        }
        var count = stack.ints(0);
        VulkanDevice.check(
                vkGetPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface, count, null),
                "surface format count");
        var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
        VulkanDevice.check(
                vkGetPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface, count, formats),
                "surface formats");
        boolean formatFound = false;
        for (var format : formats) {
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                formatFound = true;
            }
        }
        if (!formatFound) {
            throw new UnsupportedOperationException(
                    "Window needs BGRA8 UNORM with sRGB color space");
        }
        VulkanDevice.check(
                vkGetPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface, count, null),
                "present mode count");
        var modes = stack.mallocInt(count.get(0));
        VulkanDevice.check(
                vkGetPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface, count, modes),
                "present modes");
        int mode = VK_PRESENT_MODE_FIFO_KHR;
        for (int i = 0; i < modes.capacity(); i++) {
            if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                mode = VK_PRESENT_MODE_MAILBOX_KHR;
            }
        }
        int imageCount = Math.max(3, caps.minImageCount());
        if (caps.maxImageCount() > 0) {
            imageCount = Math.min(imageCount, caps.maxImageCount());
        }
        int alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
        var info =
                VkSwapchainCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .surface(surface)
                        .minImageCount(imageCount)
                        .imageFormat(VK_FORMAT_B8G8R8A8_UNORM)
                        .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                        .imageArrayLayers(1)
                        .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .preTransform(caps.currentTransform())
                        .compositeAlpha(alpha)
                        .presentMode(mode)
                        .clipped(true);
        info.imageExtent().set(w, h);
        var handle = stack.longs(0);
        VulkanDevice.check(
                vkCreateSwapchainKHR(gpu.device, info, null, handle), "create swapchain");
        swapchain = handle.get(0);
        VulkanDevice.check(
                vkGetSwapchainImagesKHR(gpu.device, swapchain, count, null),
                "swapchain image count");
        var values = stack.mallocLong(count.get(0));
        VulkanDevice.check(
                vkGetSwapchainImagesKHR(gpu.device, swapchain, count, values), "swapchain images");
        images = new long[count.get(0)];
        values.get(images);
        ready = new long[images.length];
        presented = new long[images.length];
        pending = new boolean[images.length];
        for (int i = 0; i < images.length; i++) {
            VulkanDevice.check(
                    vkCreateSemaphore(
                            gpu.device,
                            VkSemaphoreCreateInfo.calloc(stack).sType$Default(),
                            null,
                            handle),
                    "present semaphore");
            ready[i] = handle.get(0);
            VulkanDevice.check(
                    vkCreateFence(
                            gpu.device,
                            VkFenceCreateInfo.calloc(stack).sType$Default(),
                            null,
                            handle),
                    "present fence");
            presented[i] = handle.get(0);
        }
        width = w;
        height = h;
        rebuild = false;
    }

    private void transition(
            long image,
            int before,
            int after,
            int sourceAccess,
            int targetAccess,
            int sourceStage,
            int targetStage,
            MemoryStack stack) {
        var barrier =
                VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .image(image)
                        .oldLayout(before)
                        .newLayout(after)
                        .srcAccessMask(sourceAccess)
                        .dstAccessMask(targetAccess)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);
        vkCmdPipelineBarrier(gpu.command, sourceStage, targetStage, 0, null, null, barrier);
    }

    private void waitPresented(int slot) {
        if (!pending[slot]) {
            return;
        }
        VulkanDevice.check(
                vkWaitForFences(gpu.device, presented[slot], true, 30_000_000_000L),
                "present retirement");
        pending[slot] = false;
    }

    private void retireSwapchain() {
        for (int i = 0; i < images.length; i++) {
            waitPresented(i);
            if (ready[i] != 0) {
                vkDestroySemaphore(gpu.device, ready[i], null);
            }
            if (presented[i] != 0) {
                vkDestroyFence(gpu.device, presented[i], null);
            }
        }
        images = new long[0];
        ready = new long[0];
        presented = new long[0];
        pending = new boolean[0];
        if (swapchain != 0) {
            vkDestroySwapchainKHR(gpu.device, swapchain, null);
            swapchain = 0;
        }
    }

    public void close() {
        retireSwapchain();
        if (compositor != null) {
            compositor.close();
            compositor = null;
        }
        if (acquireFence != 0) {
            vkDestroyFence(gpu.device, acquireFence, null);
            acquireFence = 0;
        }
        if (surface != 0) {
            vkDestroySurfaceKHR(gpu.instance, surface, null);
            surface = 0;
        }
        gpu.resources.remove(this);
    }
}
