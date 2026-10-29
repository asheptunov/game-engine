package gpu;

import engine.*;
import engine.lights.DirectionalLight;
import engine.objects.Rect;

import math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Compare pruned traversal against all candidates on adversarial visibility and glass scenes. */
public final class TraversalCheck {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);

    public static void main(String[] args) throws Exception {
        String previous = System.getProperty("gpu.referenceTraversal");
        try {
            System.setProperty("gpu.referenceTraversal", "true");
            try (var reference = new GpuKernel()) {
                System.setProperty("gpu.referenceTraversal", "false");
                try (var confirmed = new GpuKernel()) {
                    var camera =
                            new Camera(
                                    new Vec3(0, 0, -3),
                                    new Rect(
                                            new Vec3(-1, -1, -1),
                                            new Vec3(2, 0, 0),
                                            new Vec3(0, 2, 0)));
                    var view = new RenderView(camera, 129, 129);
                    var objects = new ArrayList<SceneInstance>();
                    for (int i = 0; i < 48; i++) {
                        objects.add(
                                box(
                                        "layer" + i,
                                        3 + i * 1.25f,
                                        .5f,
                                        new Material(
                                                "color" + i, new Vec3(.2f + i / 80f, .4f, .3f))));
                    }
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            view,
                            0,
                            "many hidden layers, near-first scene order");
                    Collections.reverse(objects);
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            view,
                            0,
                            "many hidden layers, far-first scene order");
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            view,
                            4,
                            "continuations and same-object shadows");
                    var first = objects.getLast();
                    objects.add(
                            new SceneInstance(
                                    "tied",
                                    first.geometry(),
                                    first.transform(),
                                    new Material("tied red", new Vec3(.9f, .1f, .1f))));
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            view,
                            0,
                            "coincident instances on this device");

                    var glass =
                            new Material("glass", WHITE, Material.Kind.DIELECTRIC, 1.5f, Vec3.ZERO);
                    objects.clear();
                    for (int i = 0; i < 3; i++) objects.add(box("glass" + i, 1 + i, .1f, glass));
                    objects.add(
                            box(
                                    "background",
                                    5,
                                    .1f,
                                    new Material("blue", new Vec3(.1f, .3f, .8f))));
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            view,
                            8,
                            "multiple glass entries/exits and reflections");
                    var inside =
                            new RenderView(
                                    new Camera(
                                            new Vec3(0, 0, 1),
                                            new Rect(
                                                    new Vec3(-1, -1, 2),
                                                    new Vec3(2, 0, 0),
                                                    new Vec3(0, 2, 0))),
                                    129,
                                    129);
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            inside,
                            8,
                            "camera inside glass and oblique exits");
                    compare(
                            reference,
                            confirmed,
                            world(objects),
                            new RenderView(camera.withFocus(7).withAperture(.08f), 129, 129),
                            8,
                            "finite aperture through glass");
                }
            }
        } finally {
            if (previous == null) System.clearProperty("gpu.referenceTraversal");
            else System.setProperty("gpu.referenceTraversal", previous);
        }
        System.out.println("Traversal comparison checks passed.");
    }

    private static SceneInstance box(String name, float z, float halfDepth, Material material) {
        return new SceneInstance(
                name,
                BoxGeometry.UNIT,
                new Transform(new Vec3(0, 0, z), Vec3.ZERO, new Vec3(2, 2, halfDepth)),
                material);
    }

    private static WorldSnapshot world(List<SceneInstance> objects) {
        return new WorldSnapshot(
                0,
                List.copyOf(objects),
                List.of(),
                List.of(new DirectionalLight(new Vec3(-1, 2, -3), WHITE, 2)),
                new Sky(WHITE, WHITE));
    }

    private static void compare(
            GpuKernel reference,
            GpuKernel confirmed,
            WorldSnapshot world,
            RenderView view,
            int depth,
            String label)
            throws Exception {
        var settings = RenderSettings.defaults().withPathDepth(depth);
        var expected = new float[view.width() * view.height() * 3];
        var actual = new float[expected.length];
        for (int sample = 0; sample < 8; sample++) {
            reference.trace(world, view, settings, sample, expected);
            confirmed.trace(world, view, settings, sample, actual);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError(
                        label + " sample=" + sample + " RMSE=" + GpuCheck.rmse(expected, actual));
            }
        }
        if (depth == 0
                && GpuCheck.rmse(actual, GpuCheck.cpu(world, view, settings.withSampleTarget(1)))
                        >= .002) {
            throw new AssertionError(label + " differs from CPU reference");
        }
        System.out.println("PASS exact reference agreement: " + label);
    }
}
