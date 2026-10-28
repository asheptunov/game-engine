package gpu;

import static org.lwjgl.vulkan.VK10.*;
import engine.DisplayMapping;

/** Exercise the actual encoding shader at float edge cases and quantization boundaries. */
public final class GpuDisplayCheck {
    public static void main(String[] args) {
        int width = 512;
        int height = 256;
        int count = width * height;
        var random = new java.util.Random(816);
        float[] values = new float[count * 3];
        float[] edges = {0, -0f, -1, Float.NaN, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE};
        for (int i = 0; i < values.length; i++) {
            values[i] = i < edges.length ? edges[i] : Float.intBitsToFloat(random.nextInt());
        }
        try (var gpu = new VulkanDevice(); var display = new GpuDisplay(gpu);
                var source = gpu.buffer((long) count * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true)) {
            display.prepare(count);
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < 3; c++) source.mapped.putFloat(i * 16 + c * 4, values[i * 3 + c]);
                source.mapped.putFloat(i * 16 + 12, i % 3 == 0 ? 1 : 0);
            }
            for (float exposure : new float[] {0, .125f, 1, 4, 100, Float.MAX_VALUE}) {
                gpu.begin();
                display.encode(source, width, height, exposure);
                gpu.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                display.transfer();
                gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
                gpu.submit();
                int[] actual = new int[count];
                int reused = display.read(actual);
                if (reused != (count + 2) / 3) throw new AssertionError("Fog reuse reduction");
                for (int i = 0; i < count; i++) {
                    int expected = DisplayMapping.pack(values[i * 3], values[i * 3 + 1], values[i * 3 + 2], exposure);
                    int target = (height - 1 - i / width) * width + i % width;
                    if (expected != actual[target]) {
                        throw new AssertionError("Encoding exposure=" + exposure + " pixel=" + i
                                + " expected=" + expected + " actual=" + actual[target]);
                    }
                }
                System.out.println("PASS exact shader encoding exposure=" + exposure + " pixels=" + count);
            }
        }
    }
}
