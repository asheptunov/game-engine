package gpu;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.util.*;

/**
 * Real-device optical mapping, virtual depth/motion and CPU agreement at deterministic depth zero.
 */
public final class ViewRelayCheck {
    private static final int W = 64, H = 64;
    private static final Camera CAMERA =
            new Camera(
                    Vec3.ZERO,
                    new Rect(new Vec3(-.4f, -.4f, 1), new Vec3(.8f, 0, 0), new Vec3(0, .8f, 0)));
    private static final RenderView VIEW = new RenderView(CAMERA, W, H);
    private static final PolygonMesh ENTRANCE =
            PolygonMesh.parallelogram(
                    new Vec3(-.3f, .3f, 1), new Vec3(.6f, 0, 0), new Vec3(0, -.6f, 0));
    private static final Material OPTIC =
            new Material("optic", Vec3.ZERO).withViewRelay(new ViewRelay(4, .5f));
    private static final PolygonMesh WALL =
            PolygonMesh.parallelogram(
                    new Vec3(-10, 10, 8), new Vec3(20, 0, 0), new Vec3(0, -20, 0));
    private static final Material WHITE = new Material("wall", new Vec3(.8f, .8f, .8f));
    private static final Sky SKY = new Sky(new Vec3(.8f, .7f, .6f), new Vec3(.1f, .2f, .3f));

    private static WorldSnapshot world(Transform pose, boolean wall) {
        var objects = new ArrayList<SceneInstance>();
        objects.add(new SceneInstance("optic", ENTRANCE, pose, OPTIC));
        if (wall) objects.add(new SceneInstance("wall", WALL, Transform.IDENTITY, WHITE));
        return new WorldSnapshot(0, objects, List.of(), List.of(), SKY);
    }

    public static void main(String[] args) throws Exception {
        var base =
                RenderSettings.defaults()
                        .withPathDepth(0)
                        .withInteractive(false, 16.67, 0, 0)
                        .withSampleTarget(1);
        float[] raw = new float[W * H * 3];
        var skyWorld = world(Transform.IDENTITY, false);
        try (var kernel = new GpuKernel()) {
            kernel.trace(skyWorld, VIEW, base, 0, raw);
            for (int y = 4; y < H; y += 7)
                for (int x = 4; x < W; x += 7) {
                    float sx = -.4f + .8f * (x + .5f) / W, sy = -.4f + .8f * (y + .5f) / H;
                    boolean inside = Math.abs(sx) < .3f && Math.abs(sy) < .3f;
                    var d = new Vec3(sx / (inside ? 4 : 1), sy / (inside ? 4 : 1), 1).normalized();
                    float expected =
                            SKY.nadir().x()
                                    + (SKY.zenith().x() - SKY.nadir().x()) * (d.y() + 1) * .5f;
                    near(
                            raw[(y * W + x) * 3],
                            expected,
                            2e-6f,
                            "4x magnification, unchanged exterior and no depth cost");
                }
            cpuAgreement(skyWorld, base, raw);
            var blockedObjects = new ArrayList<>(skyWorld.instances());
            blockedObjects.add(
                    new SceneInstance(
                            "cover",
                            BoxGeometry.UNIT,
                            new Transform(
                                    new Vec3(0, 0, 1.25f), Vec3.ZERO, new Vec3(.2f, .2f, .04f)),
                            WHITE));
            var blockedWorld = new WorldSnapshot(1, blockedObjects, List.of(), List.of(), SKY);
            kernel.trace(blockedWorld, VIEW, base, 0, raw);
            near(raw[(H / 2 * W + W / 2) * 3], 0, 1e-7f, "cover inside scope tube blocks the view");
            cpuAgreement(blockedWorld, base, raw);
            var fog =
                    new FogRegion(
                            new Vec3(0, 0, 4),
                            new Vec3(10, 10, 10),
                            0,
                            new Vec3(.01f, .01f, .01f),
                            new Vec3(.02f, .02f, .02f),
                            .3f,
                            1.5f,
                            0,
                            0,
                            0,
                            .25f);
            var air =
                    new Atmosphere(
                            Atmosphere.Parameters.clearAir(), new Vec3(0, 1, 0), new Vec3(1, 1, 1));
            var fogWorld =
                    new WorldSnapshot(
                            2,
                            skyWorld.instances(),
                            List.of(),
                            List.of(),
                            SKY.withAtmosphere(air).withFog(List.of(fog)));
            kernel.trace(fogWorld, VIEW, base, 0, raw);
            var coarse = new float[raw.length];
            kernel.trace(fogWorld, VIEW, base.withFogDownsample(4), 0, coarse);
            require(kernel.fogDownsample == 4, "coarse fog path exercised");
            for (int y = 16; y < 48; y++)
                for (int x = 16; x < 48; x++)
                    for (int c = 0; c < 3; c++)
                        near(
                                coarse[(y * W + x) * 3 + c],
                                raw[(y * W + x) * 3 + c],
                                1e-6f,
                                "relay integrates both air segments exactly under coarse fog");
            var settings =
                    base.withPathDepth(2)
                            .withTemporal(true, 1)
                            .withDenoiser(Denoiser.NRD_RELAX)
                            .withSampleTarget(0);
            var shown = new GpuKernel.Snapshot();
            try {
                // Compile/warm NRD before the 500 ms history-expiry checks.
                for (int i = 0; i < 3; i++)
                    kernel.trace(
                            world(Transform.IDENTITY, true),
                            VIEW,
                            settings,
                            i,
                            new GpuKernel.Destination(raw, null, null, 1, shown));
                kernel.trace(
                        world(Transform.IDENTITY, true),
                        VIEW,
                        settings,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, shown));
                var first = NrdCheck.readBuffer(kernel.gpu, kernel.temporal.guide());
                var pose =
                        new Transform(
                                new Vec3(.02f, 0, .03f), new Vec3(0, 2, 0), new Vec3(1, 1, 1));
                kernel.trace(
                        world(pose, true),
                        VIEW,
                        settings,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, shown));
                var current = NrdCheck.readBuffer(kernel.gpu, kernel.temporal.guide());
                int g = (H / 2 * W + W / 2) * 24;
                require(
                        current[g + 7] > 0 && current[g + 11] > 0,
                        "scope world surface is eligible with valid history");
                near(first[g + 2], 1 + (8 - 1 - .501f) / 4, 1e-5f, "virtual depth");
                var axis = pose.vector(new Vec3(0, 0, 1)).normalized();
                var entrance = pose.point(new Vec3(0, 0, 1));
                var virtual = vec(current, g);
                // Independently invert the current virtual scene to recover the real wall hit.
                var actual = virtual.add(axis.scale(3 * virtual.sub(entrance).dot(axis) + .501f));
                near(actual.z(), 8, 2e-5f, "virtual point maps back onto actual wall");
                var expectedPrevious =
                        new Vec3(actual.x(), actual.y(), 1 + (actual.z() - 1 - .501f) / 4);
                require(
                        vec(current, g + 8).sub(expectedPrevious).length() < 2e-5,
                        "previous scope pose participates in motion");
                require(
                        vec(current, g + 8).sub(virtual).length() > .01,
                        "moving scope has motion even with static world and camera");
                var primary =
                        new Vec3(-.4f + .8f * (W / 2 + .5f) / W, -.4f + .8f * (H / 2 + .5f) / H, 1)
                                .normalized();
                require(
                        virtual.normalized().sub(primary).length() < 2e-5,
                        "virtual hit projects to the sampled pixel");
                kernel.trace(
                        world(Transform.IDENTITY, false),
                        VIEW,
                        settings,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, shown));
                kernel.trace(
                        world(pose, false),
                        VIEW,
                        settings,
                        0,
                        new GpuKernel.Destination(raw, null, null, 1, shown));
                var sky = NrdCheck.readBuffer(kernel.gpu, kernel.temporal.guide());
                require(sky[g + 3] < 0 && sky[g + 11] > 0, "sky has valid prior direction");
                require(
                        vec(sky, g).sub(primary).length() < 2e-5,
                        "current sky direction projects to primary pixel");
                require(
                        vec(sky, g).sub(vec(sky, g + 8)).length() > .01,
                        "sky motion includes scope rotation");
                for (float value : shown.read(kernel.gpu))
                    require(Float.isFinite(value), "finite reconstruction");
            } finally {
                if (shown.source != null) shown.source.close();
            }
        }
        System.out.println(
                "View relay checks passed: CPU/GPU mapping, mask, depth zero, virtual surfaces and"
                        + " sky motion.");
    }

    private static void cpuAgreement(WorldSnapshot world, RenderSettings settings, float[] gpu)
            throws Exception {
        try (var session = RenderEngine.openSession(world, VIEW, settings, "cpu")) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                if (session.failure() != null) throw session.failure();
                try (var image = session.acquireImage()) {
                    if (image != null) {
                        for (int y = 0; y < H; y++)
                            for (int x = 0; x < W; x++)
                                for (int c = 0; c < 3; c++)
                                    near(
                                            image.rawPixels().value(c, x, y),
                                            gpu[(y * W + x) * 3 + c],
                                            3e-6f,
                                            "CPU/GPU agreement");
                        return;
                    }
                }
                Thread.sleep(1);
            }
            throw new AssertionError("CPU render timeout");
        }
    }

    private static Vec3 vec(float[] data, int offset) {
        return new Vec3(data[offset], data[offset + 1], data[offset + 2]);
    }

    private static void near(float a, float b, float tolerance, String message) {
        require(Math.abs(a - b) < tolerance, message + ": " + a + " vs " + b);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
