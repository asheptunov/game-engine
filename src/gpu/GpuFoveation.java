package gpu;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** GPU-owner-only compact ray locations; cached until the internal grid changes. */
final class GpuFoveation {
    VulkanDevice.Buffer locations;
    int count;
    private int width;
    private int height;

    boolean prepare(VulkanDevice gpu, int nextWidth, int nextHeight, boolean enabled) {
        if (!enabled) {
            if (locations != null) {
                return false;
            }
            locations = gpu.buffer(4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            return true;
        }
        if (width == nextWidth && height == nextHeight) {
            return false;
        }
        int nextCount = countLocations(nextWidth, nextHeight);
        var replacement =
                gpu.buffer((long) nextCount * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        writeLocations(replacement, nextWidth, nextHeight);
        if (locations != null) {
            locations.close();
        }
        locations = replacement;
        width = nextWidth;
        height = nextHeight;
        count = nextCount;
        return true;
    }

    private static int countLocations(int width, int height) {
        int count = 0;
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < width; x += 4) {
                int rate = rate(x, y, width, height);
                count +=
                        ((Math.min(4, width - x) + rate - 1) / rate)
                                * ((Math.min(4, height - y) + rate - 1) / rate);
            }
        }
        return count;
    }

    private static void writeLocations(VulkanDevice.Buffer replacement, int width, int height) {
        int index = 0;
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < width; x += 4) {
                int rate = rate(x, y, width, height);
                for (int dy = 0; dy < Math.min(4, height - y); dy += rate) {
                    for (int dx = 0; dx < Math.min(4, width - x); dx += rate) {
                        // Twelve bits per coordinate cover the GPU's 3840-pixel axis limit.
                        int encoded =
                                (x + dx)
                                        | ((y + dy) << 12)
                                        | (Integer.numberOfTrailingZeros(rate) << 24);
                        replacement.mapped.putInt(index++ * 4, encoded);
                    }
                }
            }
        }
    }

    static int rate(int x, int y, int width, int height) {
        float u = Math.min(x + 2, width - .5f) / width * 2 - 1;
        float v = Math.min(y + 2, height - .5f) / height * 2 - 1;
        float radius = (float) Math.sqrt(u * u + v * v);
        // A fixed screen-space hash spreads changes over a radial interval, rather than
        // changing every tile at a ring. Never vary this with frame/sample/camera state:
        // each anchor must retain the same footprint throughout progressive accumulation.
        int hash = (x / 4) * 0x1f123bb5 ^ (y / 4) * 0x5f356495;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        float threshold = (hash >>> 8) / 16777216f;
        if (radius < .7f) {
            return threshold < falloff(radius, .5f, .7f) ? 2 : 1;
        }
        return threshold < falloff(radius, .75f, .95f) ? 4 : 2;
    }

    private static float falloff(float radius, float start, float end) {
        float t = Math.clamp((radius - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
