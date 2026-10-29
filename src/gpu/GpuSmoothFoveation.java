package gpu;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

import engine.FoveationQuality;

import java.util.ArrayList;

/** Owner-thread sampling coordinates and surface guides for continuous-spacing reconstruction. */
final class GpuSmoothFoveation {
    VulkanDevice.Buffer layout;
    VulkanDevice.Buffer guides;
    int count;
    private int width;
    private int height;
    private FoveationQuality quality;

    boolean prepare(
            VulkanDevice gpu,
            int nextWidth,
            int nextHeight,
            boolean enabled,
            FoveationQuality nextQuality) {
        if (!enabled) {
            if (layout != null) {
                return false;
            }
            layout = gpu.buffer(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            guides = gpu.buffer(32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            return true;
        }
        if (width == nextWidth && height == nextHeight && nextQuality.equals(quality)) {
            return false;
        }
        float[] x = axis(nextWidth, nextQuality);
        float[] y = axis(nextHeight, nextQuality);
        int xMap = 4 + x.length + y.length;
        int yMap = xMap + nextWidth;
        var replacement =
                gpu.buffer(
                        (long) (yMap + nextHeight) * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        replacement.mapped.putInt(0, x.length).putInt(4, y.length).putInt(8, xMap).putInt(12, yMap);
        write(replacement, 4, x);
        write(replacement, 4 + x.length, y);
        write(replacement, xMap, inverse(x, nextWidth));
        write(replacement, yMap, inverse(y, nextHeight));
        var nextGuides =
                gpu.buffer(
                        (long) x.length * y.length * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        if (layout != null) {
            layout.close();
            guides.close();
        }
        layout = replacement;
        guides = nextGuides;
        width = nextWidth;
        height = nextHeight;
        quality = nextQuality;
        count = x.length * y.length;
        return true;
    }

    private static void write(VulkanDevice.Buffer target, int offset, float[] values) {
        for (int i = 0; i < values.length; i++) {
            target.mapped.putFloat((offset + i) * 4, values[i]);
        }
    }

    /**
     * Pixel-space sample centers; retain exact pixel centers over the central half of each axis.
     */
    static float[] axis(int size) {
        return axis(size, FoveationQuality.defaults());
    }

    static float[] axis(int size, FoveationQuality quality) {
        double center = size * .5;
        double edge = center - .5;
        var positive = new ArrayList<Double>();
        double position = size % 2 == 0 ? .5 : 0;
        positive.add(position);
        while (position < edge) {
            double step = spacing(position / center, quality);
            // Midpoint evaluation keeps spacing changes gradual as the lattice stretches.
            step = spacing((position + step * .5) / center, quality);
            double next = Math.min(edge, position + step);
            if (next - position < 1) {
                positive.set(positive.size() - 1, edge);
            } else {
                positive.add(next);
            }
            position = next;
        }
        int mirrored = positive.size() - (size % 2);
        float[] result = new float[positive.size() + mirrored];
        for (int i = 0; i < mirrored; i++) {
            result[i] = (float) (center - positive.get(positive.size() - 1 - i));
        }
        for (int i = 0; i < positive.size(); i++) {
            result[mirrored + i] = (float) (center + positive.get(i));
        }
        return result;
    }

    private static double spacing(double radius, FoveationQuality quality) {
        double t =
                Math.clamp(
                        (radius - quality.centerFraction()) / (1 - quality.centerFraction()), 0, 1);
        return 1 + (quality.edgeSpacing() - 1) * t * t * (3 - 2 * t);
    }

    /** Fractional lattice coordinate for every output pixel center; endpoints are clamped. */
    static float[] inverse(float[] positions, int size) {
        float[] result = new float[size];
        int lower = 0;
        for (int pixel = 0; pixel < size; pixel++) {
            float center = pixel + .5f;
            while (lower + 1 < positions.length && positions[lower + 1] <= center) {
                lower++;
            }
            result[pixel] =
                    lower == positions.length - 1
                            ? lower
                            : lower
                                    + (center - positions[lower])
                                            / (positions[lower + 1] - positions[lower]);
        }
        return result;
    }
}
