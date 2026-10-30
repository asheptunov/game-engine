package gpu;

/** Hidden real-device check of the signed Vulkan upscaler and its camera sample sequence. */
public final class FsrContextCheck {
    public static void main(String[] args) {
        try (var gpu = new VulkanDevice();
                var fsr = new GpuFsrLibrary(gpu, 960, 540, 1920, 1080)) {
            System.out.println("Provider=" + fsr.version + " memory=" + fsr.memory);
            for (int i = 0; i < 33; i++) {
                fsr.jitter(i);
                if (!Float.isFinite(fsr.jitterX)
                        || !Float.isFinite(fsr.jitterY)
                        || Math.abs(fsr.jitterX) > .5
                        || Math.abs(fsr.jitterY) > .5)
                    throw new AssertionError("Invalid camera jitter");
                if (i < 4) System.out.println(i + ": " + fsr.jitterX + ", " + fsr.jitterY);
            }
        }
    }
}
