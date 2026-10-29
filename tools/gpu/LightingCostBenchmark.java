package gpu;

import static org.lwjgl.vulkan.VK10.*;

import engine.*;

import game.*;

import math.Vec3;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.*;

/** Fixed-camera GPU cost and fallback coverage; no game simulation or presentation. */
public final class LightingCostBenchmark {
    public static void main(String[] args) {
        var world = AdventureWorld.create();
        var light = new DayNightCycle().atmosphericLighting(Atmosphere.Parameters.clearAir());
        world =
                new WorldSnapshot(
                        0, world.instances(), List.of(), List.of(light.sun()), light.sky());
        var navigation = new GameNavigation();
        navigation.fixedResolution(1920, 1080);
        navigation.optics(0, 12);
        navigation.pose(new Vec3(0, 2.4f, -6), 0, -.08);
        var view = navigation.view();
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(8)
                        .withTemporal(true, 1)
                        .withLightingScale(.5);
        try (var kernel = new GpuKernel()) {
            System.out.println(
                    "mode,medianGpuMs,medianReconstructionMs,medianVisibilityMs,surfaceReusePercent,skyReusePercent,fallbackPercent");
            for (double scale : new double[] {1, .5, 2. / 3}) {
                settings = settings.withLightingScale(scale);
                var input = GpuLighting.sampled(view, settings);
                var raw = new GpuKernel.Snapshot();
                double[] total = new double[30],
                        reconstruction = new double[30],
                        visibility = new double[30];
                for (int frame = -10; frame < 30; frame++) {
                    navigation.pose(new Vec3(frame * .0001f, 2.4f, -6), 0, -.08);
                    var moved =
                            new RenderView(
                                    navigation.view().camera(), input.width(), input.height());
                    var output = new RenderView(moved.camera(), view.width(), view.height());
                    kernel.trace(
                            world,
                            moved,
                            settings,
                            0,
                            new GpuKernel.Destination(null, null, raw, 1, null, output));
                    if (frame >= 0) {
                        total[frame] = kernel.executeNanos / 1e6;
                        reconstruction[frame] = kernel.reconstructionNanos / 1e6;
                        visibility[frame] = kernel.visibilityNanos / 1e6;
                    }
                }
                double[] coverage = scale == 1 ? new double[3] : coverage(kernel);
                Arrays.sort(total);
                Arrays.sort(reconstruction);
                Arrays.sort(visibility);
                System.out.printf(
                        Locale.ROOT,
                        "%.6f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f%n",
                        scale,
                        total[15],
                        reconstruction[15],
                        visibility[15],
                        coverage[0],
                        coverage[1],
                        coverage[2]);
            }
        }
    }

    private static double[] coverage(GpuKernel kernel) {
        var gpu = kernel.gpu;
        var output = kernel.lighting.output;
        try (var staging = gpu.buffer(output.size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
                var stack = MemoryStack.stackPush()) {
            gpu.begin();
            gpu.barrier(
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdCopyBuffer(
                    gpu.command,
                    output.handle,
                    staging.handle,
                    VkBufferCopy.calloc(1, stack).size(output.size));
            gpu.barrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_ACCESS_HOST_READ_BIT);
            gpu.submit();
            long surface = 0, sky = 0, count = output.size / 16;
            for (int p = 0; p < count; p++) {
                float value = staging.mapped.getFloat(p * 16 + 12);
                if (value == 1) surface++;
                else if (value == 2) sky++;
            }
            return new double[] {
                100. * surface / count, 100. * sky / count, 100. * (count - surface - sky) / count
            };
        }
    }
}
