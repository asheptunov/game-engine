package gpu;

import engine.*;
import engine.lights.DirectionalLight;
import engine.objects.Rect;
import game.*;
import math.Vec3;
import java.util.List;

/** Headless real-device checks and forest previews for thin diffuse foliage. */
public final class ThinLeafCheck {
    private static final Vec3 WHITE = new Vec3(1, 1, 1);

    public static void main(String[] args) throws Exception {
        var leaf = new Material("leaf", new Vec3(.3f, .6f, .1f), Material.Kind.THIN_LEAF);
        var mesh = PolygonMesh.surface(
                List.of(new PolygonMesh.Vertex(0, new Vec3(-10, -10, 0)),
                        new PolygonMesh.Vertex(1, new Vec3(10, -10, 0)),
                        new PolygonMesh.Vertex(2, new Vec3(10, 10, 0)),
                        new PolygonMesh.Vertex(3, new Vec3(-10, 10, 0))),
                List.of(new PolygonMesh.Face(0, List.of(0L, 1L, 2L, 3L))), 4, 1);
        var sheet = new SceneInstance("sheet", mesh, Transform.IDENTITY, leaf);
        var view = new RenderView(new Camera(new Vec3(0, 0, -3),
                new Rect(new Vec3(-.5f, -.5f, -2), new Vec3(1, 0, 0), new Vec3(0, 1, 0))), 64, 64);
        var settings = RenderSettings.defaults().withPathDepth(0).withSampleTarget(1);
        try (var kernel = new GpuKernel()) {
            var pixels = new float[64 * 64 * 3];
            for (int side : new int[] {-1, 1}) {
                var world = new WorldSnapshot(0, List.of(sheet), List.of(),
                        List.of(new DirectionalLight(new Vec3(0, 0, side), WHITE, 2)), Sky.BLACK);
                kernel.trace(world, view, settings, 0, pixels);
                require(GpuCheck.rmse(pixels, GpuCheck.cpu(world, view, settings)) < 1e-5,
                        "CPU/GPU agree for sunlight on leaf side " + side);
                require(Math.abs(pixels[1] - .6 / Math.PI) < 1e-5,
                        "half-energy green sunlight on side " + side);
                var opaque = new WorldSnapshot(1,
                        List.of(sheet.withMaterial(leaf.withKind(Material.Kind.DIFFUSE))),
                        List.of(), world.lights(), Sky.BLACK);
                kernel.trace(opaque, view, settings, 0, pixels);
                require(Math.abs(pixels[1] - (side == 1 ? 0 : 1.2 / Math.PI)) < 1e-5,
                        "opaque material remains unchanged");
            }
            var blocked = new WorldSnapshot(2,
                    List.of(sheet, sheet.withMaterial(new Material("blocker", WHITE))
                            .withTransform(new Transform(new Vec3(0, 0, 1), Vec3.ZERO, WHITE))),
                    List.of(), List.of(new DirectionalLight(new Vec3(0, 0, 1), WHITE, 2)), Sky.BLACK);
            kernel.trace(blocked, view, settings, 0, pixels);
            require(pixels[1] == 0, "solid occluder blocks leaf backlighting");
            var sky = new WorldSnapshot(3, List.of(sheet), List.of(), List.of(), new Sky(WHITE, WHITE));
            kernel.trace(sky, view, settings.withPathDepth(1), 0, pixels);
            require(GpuCheck.rmse(pixels, GpuCheck.cpu(sky, view, settings.withPathDepth(1))) < 1e-5,
                    "both hemispheres preserve sky energy with one continuation");
            var optical = leaf.withLeaf(new LeafOptics(.6f, new Vec3(.12f, .55f, .035f), .24f, .65f, 24));
            for (float angle : new float[] {0, .4f, 1.1f}) {
                var sun = new Vec3((float) Math.sin(angle), 0, (float) Math.cos(angle));
                var world = new WorldSnapshot(4, List.of(sheet.withMaterial(optical)), List.of(),
                        List.of(new DirectionalLight(sun, WHITE, 2)), Sky.BLACK);
                kernel.trace(world, view, settings, 0, pixels);
                require(GpuCheck.rmse(pixels, GpuCheck.cpu(world, view, settings)) < 2e-5,
                        "CPU/GPU directional leaf angle " + angle);
                GpuCheck.image(pixels, 64, 64, "leaf-angle-" + angle);
            }
            var opticalSky = new WorldSnapshot(5, List.of(sheet.withMaterial(optical)), List.of(), List.of(), new Sky(WHITE, WHITE));
            var bounce = settings.withPathDepth(1).withSampleTarget(128).withSamplesPerBatch(8);
            for (int i = 0; i < 128; i++) kernel.trace(opticalSky, view, bounce, i, pixels);
            require(GpuCheck.rmse(pixels, GpuCheck.cpu(opticalSky, view, bounce)) < .04,
                    "CPU/GPU stochastic directional transmission agrees");
            forest(kernel);
        }
    }

    private static void forest(GpuKernel kernel) throws Exception {
        var lighting = GameLighting.defaults();
        var world = new WorldSnapshot(0, AdventureWorld.create().instances(), List.of(),
                List.of(lighting.sun()), lighting.sky());
        var opaque = new WorldSnapshot(1, world.instances().stream().map(instance ->
                instance.material().kind() == Material.Kind.THIN_LEAF
                        ? instance.withMaterial(instance.material().withKind(Material.Kind.DIFFUSE)
                                .withColor(instance.material().color().scale(.5f))) : instance).toList(),
                List.of(), world.lights(), world.sky());
        var nav = new GameNavigation();
        nav.fixedResolution(480, 300);
        var settings = RenderSettings.defaults().withPathDepth(3);
        for (var entry : List.of(opaque, world)) {
            String name = entry == world ? "leaf-transmission" : "leaf-opaque";
            var pixels = new float[480 * 300 * 3];
            kernel.trace(entry, nav.view(), settings, 0, pixels);
            long start = System.nanoTime();
            for (int sample = 1; sample < 32; sample++) {
                kernel.trace(entry, nav.view(), settings, sample, pixels);
            }
            System.out.printf("%s mean host frame %.2fms%n", name, (System.nanoTime()-start)/31e6);
            GpuCheck.image(pixels, 480, 300, name);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
}
