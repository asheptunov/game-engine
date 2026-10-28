package gpu;

import engine.*;
import engine.lights.DirectionalLight;
import engine.objects.Rect;

import math.Vec3;

import java.util.*;

/** Compare retained BUILD storage against allocation on every moving frame, on the real device. */
public final class TlasReuseCheck {
    public static void main(String[] args) {
        var view =
                new RenderView(
                        new Camera(
                                new Vec3(0, 0, -3),
                                new Rect(
                                        new Vec3(-1, -1, -1),
                                        new Vec3(2, 0, 0),
                                        new Vec3(0, 2, 0))),
                        96,
                        96);
        var settings = RenderSettings.defaults().withPathDepth(3).withSampleTarget(1);
        var worlds = new ArrayList<WorldSnapshot>();
        for (int frame = 0; frame < 36; frame++) worlds.add(world(frame));
        var expected = new ArrayList<float[]>();
        System.setProperty("game.rebuildGpuTlas", "true");
        try (var reference = new GpuKernel()) {
            for (var world : worlds) {
                var pixels = new float[96 * 96 * 3];
                reference.trace(world, view, settings, 0, pixels);
                expected.add(pixels);
            }
        }
        System.clearProperty("game.rebuildGpuTlas");
        try (var kernel = new GpuKernel()) {
            long stableBytes = 0, previousHandle = 0;
            int previousCount = 0;
            for (int i = 0; i < worlds.size(); i++) {
                var world = worlds.get(i);
                var pixels = new float[96 * 96 * 3];
                kernel.trace(world, view, settings, 0, pixels);
                if (!Arrays.equals(pixels, expected.get(i)))
                    throw new AssertionError("Raw RGB mismatch at frame " + i);
                if (i > 0
                        && previousCount == world.instances().size()
                        && previousHandle != kernel.scene.top.handle)
                    throw new AssertionError("Same-count motion replaced the TLAS");
                if (i == 24) stableBytes = kernel.gpu.allocatedBytes;
                if (i > 24 && kernel.gpu.allocatedBytes != stableBytes)
                    throw new AssertionError("Retained memory failed to plateau");
                previousCount = world.instances().size();
                previousHandle = kernel.scene.top.handle;
            }
            if (kernel.scene.topAllocations != 3 || kernel.scene.topReuses != 33)
                throw new AssertionError(
                        "Unexpected rebuild policy: "
                                + kernel.scene.topAllocations
                                + " allocations, "
                                + kernel.scene.topReuses
                                + " reuses");
            System.out.println(
                    "PASS 36 exact seeded RGB frames, translation/rotation/scale, materials/order,"
                        + " remove/add, 3 allocations/33 reuses, bounded memory");
        }
    }

    private static WorldSnapshot world(int frame) {
        var objects = new ArrayList<SceneInstance>();
        int count = frame >= 12 && frame < 20 ? 3 : 4;
        for (int i = 0; i < count; i++) {
            float x = (i - 1.5f) * 1.1f + (float) Math.sin(frame * .15 + i) * .3f;
            var transform =
                    new Transform(
                            new Vec3(x, 0, 3 + i * .2f),
                            new Vec3(frame, frame * 2 + i * 10, 0),
                            new Vec3(.4f + frame * .002f, .6f, .4f));
            var material =
                    new Material(
                            "color-" + i,
                            new Vec3(.2f + i * .15f, .7f - i * .1f, frame < 8 ? .3f : .6f));
            objects.add(new SceneInstance("object-" + i, BoxGeometry.UNIT, transform, material));
        }
        if (frame >= 8 && frame < 12) Collections.reverse(objects);
        return new WorldSnapshot(
                frame,
                objects,
                List.of(),
                List.of(new DirectionalLight(new Vec3(-1, 2, -3), new Vec3(1, 1, 1), 1)),
                Sky.BLACK);
    }
}
