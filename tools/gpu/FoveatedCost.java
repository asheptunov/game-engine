package gpu;

import engine.*;

import game.*;

import math.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Isolated packed-readback cost; no Swing, moving combat or physical presentation. */
public final class FoveatedCost {
    public static void main(String[] args) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(1920, 1080);
        navigation.pose(new Vec3(10.879049f, 2.7514815f, 22.884184f), 1.251, .079);
        var clock = new DayNightCycle();
        clock.hour(6.396);
        var light = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var world =
                new WorldSnapshot(
                        0,
                        AdventureWorld.create().instances(),
                        List.of(),
                        List.of(light.sun()),
                        light.sky());
        var settings = RenderSettings.defaults().withPathDepth(8);
        var packed = new int[1920 * 1080];
        var snapshot = new GpuKernel.Snapshot();
        System.out.println("round,mode,medianHostMs,p95HostMs,medianGpuMs,medianReadbackMs");
        try (var kernel = new GpuKernel()) {
            for (int round = 0; round < 3; round++) {
                for (String mode :
                        round % 2 == 0
                                ? new String[] {"uniform", "sparse", "compact"}
                                : new String[] {"compact", "sparse", "uniform"}) {
                    System.setProperty(
                            "gpu.sparseFoveation", Boolean.toString(mode.equals("sparse")));
                    var current = settings.withFoveated(!mode.equals("uniform"));
                    double[] host = new double[100], gpu = new double[100], read = new double[100];
                    for (int i = -30; i < 100; i++) {
                        kernel.trace(
                                world, navigation.view(), current, 0, null, packed, snapshot, 1);
                        if (i >= 0) {
                            host[i] = kernel.hostNanos / 1e6;
                            gpu[i] = kernel.executeNanos / 1e6;
                            read[i] = kernel.readbackNanos / 1e6;
                        }
                    }
                    Arrays.sort(host);
                    Arrays.sort(gpu);
                    Arrays.sort(read);
                    System.out.printf(
                            Locale.ROOT,
                            "%d,%s,%.6f,%.6f,%.6f,%.6f%n",
                            round,
                            mode,
                            host[49],
                            host[94],
                            gpu[49],
                            read[49]);
                }
            }
        } finally {
            System.clearProperty("gpu.sparseFoveation");
        }
    }
}
