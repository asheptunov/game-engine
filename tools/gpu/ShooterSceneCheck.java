package gpu;

import engine.RenderSettings;
import engine.SceneInstance;
import engine.Transform;
import engine.WorldSnapshot;
import game.AdventureWorld;
import game.GameLighting;
import game.GameNavigation;
import game.ShooterGame;
import math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Window-free equal-work patrol animation benchmark and retained-scene correctness check. */
public final class ShooterSceneCheck {
    public static void main(String[] args) throws Exception {
        boolean verify = args.length > 0 && args[0].equals("verify");
        var scenery = AdventureWorld.create().instances();
        var patrols = new ShooterGame(scenery).instances();
        var navigation = new GameNavigation();
        navigation.fixedResolution(320, 200);
        var view = navigation.view();
        var settings = RenderSettings.defaults().withPathDepth(2).withSampleTarget(1);
        var pixels = new float[320 * 200 * 3];
        double[] preparation = new double[20];
        double[] total = new double[20];
        WorldSnapshot last = null;
        try (var kernel = new GpuKernel()) {
            GpuScene retained = null;
            long residentBytes = -1;
            for (int frame = 0; frame < 25; frame++) {
                var objects = new ArrayList<>(scenery);
                for (var patrol : patrols) {
                    var t = patrol.transform();
                    objects.add(patrol.withTransform(new Transform(t.position.add(new Vec3(frame * .03f, 0, 0)), Vec3.ZERO, t.scale)));
                }
                last = world(frame, objects);
                long start = System.nanoTime();
                kernel.trace(last, view, settings, 0, pixels);
                if (frame >= 5) {
                    total[frame - 5] = (System.nanoTime() - start) / 1e6;
                    preparation[frame - 5] = kernel.prepareNanos / 1e6;
                }
                if (retained == null) { retained = kernel.scene; }
                if (verify && kernel.scene != retained) { throw new AssertionError("Patrol motion rebuilt mesh geometry"); }
                if (residentBytes < 0) { residentBytes = kernel.gpu.allocatedBytes; }
                if (verify && kernel.gpu.allocatedBytes != residentBytes) { throw new AssertionError("Animated scene retains retired GPU allocations"); }
            }
            Arrays.sort(preparation);
            Arrays.sort(total);
            System.out.printf(java.util.Locale.ROOT, "320x200, depth=2, 1 spp: median prepare %.3f ms, median total %.3f ms%n", preparation[10], total[10]);
            if (verify) {
                compareFresh(last, view, settings, pixels);
                var removed = new ArrayList<>(last.instances());
                removed.removeLast();
                java.util.Collections.swap(removed, removed.size() - 1, removed.size() - 2);
                last = world(30, removed);
                kernel.trace(last, view, settings, 0, pixels);
                if (kernel.scene != retained) { throw new AssertionError("Removal rebuilt static meshes"); }
                compareFresh(last, view, settings, pixels);
                System.out.println("PASS animated transforms, bounded GPU memory, removal/reordering and fresh-build pixel agreement");
            }
        }
    }

    private static WorldSnapshot world(long revision, List<SceneInstance> objects) {
        var light = GameLighting.defaults();
        return new WorldSnapshot(revision, objects, List.of(), List.of(light.sun()), light.sky());
    }

    private static void compareFresh(WorldSnapshot world, engine.RenderView view,
                                     RenderSettings settings, float[] actual) {
        try (var fresh = new GpuKernel()) {
            var expected = new float[actual.length];
            fresh.trace(world, view, settings, 0, expected);
            double error = 0;
            for (int i = 0; i < actual.length; i++) { error = Math.max(error, Math.abs(actual[i] - expected[i])); }
            if (error > .00001) { throw new AssertionError("Retained scene differs: " + error); }
        }
    }
}
