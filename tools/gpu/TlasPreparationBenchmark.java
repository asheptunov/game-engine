package gpu;

import engine.*;

import game.AdventureWorld;

import math.Vec3;

import java.util.*;

/** Isolate host preparation with the real ruins geometry and one moving instance. No window. */
public final class TlasPreparationBenchmark {
    public static void main(String[] args) {
        var source = AdventureWorld.create();
        var worlds = new ArrayList<WorldSnapshot>();
        for (int frame = 0; frame < 120; frame++) {
            var objects = new ArrayList<>(source.instances());
            var object = objects.getLast();
            var pose = object.transform();
            objects.set(
                    objects.size() - 1,
                    new SceneInstance(
                            object.name(),
                            object.geometry(),
                            new Transform(
                                    pose.position.add(
                                            new Vec3((float) Math.sin(frame * .1) * .1f, 0, 0)),
                                    pose.rotation,
                                    pose.scale),
                            object.material()));
            worlds.add(
                    new WorldSnapshot(
                            frame + 1,
                            objects,
                            source.legacyObjects(),
                            source.lights(),
                            source.sky()));
        }
        System.out.println(
                "round,mode,instances,medianPrepareMs,p95PrepareMs,liveDeviceBytes,topAllocations,topReuses");
        for (int round = 0; round < 3; round++)
            for (boolean rebuild : new boolean[] {round % 2 == 0, round % 2 != 0}) {
                System.setProperty("game.rebuildGpuTlas", Boolean.toString(rebuild));
                try (var gpu = new VulkanDevice();
                        var scene = new GpuScene(gpu, source)) {
                    var times = new ArrayList<Double>();
                    var before = source;
                    for (int i = 0; i < worlds.size(); i++) {
                        var after = worlds.get(i);
                        long start = System.nanoTime();
                        scene.updateInstances(before, after);
                        if (i >= 20) times.add((System.nanoTime() - start) / 1e6);
                        before = after;
                    }
                    Collections.sort(times);
                    System.out.printf(
                            Locale.ROOT,
                            "%d,%s,%d,%.4f,%.4f,%d,%d,%d%n",
                            round,
                            rebuild ? "allocate" : "reuse",
                            source.instances().size(),
                            times.get(49),
                            times.get(94),
                            gpu.allocatedBytes,
                            scene.topAllocations,
                            scene.topReuses);
                }
            }
    }
}
