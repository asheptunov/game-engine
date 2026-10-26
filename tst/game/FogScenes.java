package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.Atmosphere;
import engine.FogRegion;
import engine.Material;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.SceneInstance;
import engine.Sky;
import engine.Transform;
import engine.WorldSnapshot;
import engine.lights.DirectionalLight;

import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/** Same fixed rays on both backends; raw linear values and display previews are retained. */
public final class FogScenes {
    private FogScenes() {}

    public static void main(String[] args) throws Exception {
        String backend = args[0];
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        boolean full = args.length > 2;
        StringBuilder rows = new StringBuilder("backend,case,spp,traceMs,rmse,maxError\n");
        for (double hour : new double[] {7.5, 12, 17.8, 18.2}) {
            for (boolean closed : new boolean[] {false, true}) {
                fixture(
                        backend,
                        output,
                        rows,
                        hour,
                        closed,
                        full ? 480 : 64,
                        full ? 300 : 64,
                        full ? 4 : 1);
            }
        }
        shadowAndLifecycle(backend);
        Files.writeString(output.resolve(backend + "-fog-scenes.csv"), rows);
        System.out.println(rows);
    }

    static void conformance(String backend, Path output) throws Exception {
        fixture(backend, output, new StringBuilder(), 7.5, false, 64, 64, 1);
        shadowAndLifecycle(backend);
    }

    private static void fixture(
            String backend,
            Path output,
            StringBuilder rows,
            double hour,
            boolean closed,
            int width,
            int height,
            int samples)
            throws Exception {
        var source = AdventureWorld.create();
        var clock = new DayNightCycle();
        clock.hour(hour);
        var light = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var world =
                new WorldSnapshot(
                        1,
                        RuinsMist.scenery(source.instances(), true, closed, 0),
                        List.of(),
                        List.of(light.sun()),
                        light.sky().withFog(List.of(RuinsMist.region(0))));
        var navigation = new GameNavigation();
        navigation.fixedResolution(width, height);
        navigation.pose(new Vec3(8, 4, 18), .85, .02);
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(2)
                        .withSamplesPerBatch(1)
                        .withSampleTarget(samples);
        String name =
                "fog-" + hour + "-" + (closed ? "closed" : "open") + "-" + width + "x" + height;
        try (var session = RenderEngine.openSession(world, navigation.view(), settings, backend)) {
            for (int sample : samples == 1 ? new int[] {1} : new int[] {1, samples}) {
                try (var image = AtmosphereScenes.await(session, sample)) {
                    var errors =
                            AtmosphereScenes.compareOrSave(
                                    backend,
                                    output.resolve(name + "-" + sample + ".rgb"),
                                    BackendConformance.copy(image));
                    ImageIO.write(
                            GameRenderer.copyImage(image),
                            "png",
                            output.resolve(name + "-" + backend + "-" + sample + ".png").toFile());
                    rows.append(
                            String.format(
                                    java.util.Locale.ROOT,
                                    "%s,%s,%d,%.4f,%.8f,%.8f%n",
                                    backend,
                                    name,
                                    sample,
                                    image.traceNanos() / 1e6,
                                    errors[0],
                                    errors[1]));
                }
            }
        }
    }

    static void shadowAndLifecycle(String backend) throws Exception {
        var nav = new GameNavigation();
        nav.fixedResolution(64, 64);
        nav.pose(new Vec3(0, 0, -4), 0, 0);
        var fog =
                new FogRegion(
                        Vec3.ZERO,
                        new Vec3(2, 2, 2),
                        0,
                        new Vec3(.06f, .06f, .06f),
                        Vec3.ZERO,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0);
        var light = new DirectionalLight(new Vec3(1, 0, 0), new Vec3(1, 1, 1), 2);
        var blocker =
                new SceneInstance(
                        "thin screen",
                        SceneInstance.box(),
                        new Transform(new Vec3(2.1f, 0, 0), Vec3.ZERO, new Vec3(.01f, 5, 5)),
                        new Material("black", Vec3.ZERO));
        var distant =
                blocker.withTransform(
                        new Transform(new Vec3(100, 100, 0), Vec3.ZERO, new Vec3(.01f, 5, 5)));
        var open =
                new WorldSnapshot(
                        1,
                        List.of(distant),
                        List.of(),
                        List.of(light),
                        Sky.BLACK.withFog(List.of(fog)));
        var closed = new WorldSnapshot(2, List.of(blocker), List.of(), List.of(light), open.sky());
        var settings = RenderSettings.defaults().withPathDepth(0).withSampleTarget(1);
        try (var session = RenderEngine.openSession(open, nav.view(), settings, backend);
                var held = AtmosphereScenes.await(session, 1)) {
            var before = BackendConformance.copy(held);
            assertTrue(sum(before) > .1);
            session.update(closed, nav.view(), settings);
            try (var image = AtmosphereScenes.await(session, 1)) {
                assertTrue(sum(BackendConformance.copy(image)) < sum(before) * .01);
            }
            assertEquals(before, BackendConformance.copy(held));
            for (int i = 0; i < 5; i++) {
                var shifted =
                        new FogRegion(
                                new Vec3(i * .1f, 0, 0),
                                fog.halfExtent(),
                                i * 5,
                                fog.scattering(),
                                fog.absorption(),
                                .5f + i * .2f,
                                1,
                                .4f,
                                .3f,
                                2,
                                .2f);
                var edited =
                        new WorldSnapshot(
                                10 + i,
                                open.instances(),
                                List.of(),
                                open.lights(),
                                open.sky().withFog(List.of(fog, shifted)));
                session.update(edited, nav.view(), settings);
                session.request();
            }
            session.update(open, nav.view(), settings);
            try (var image = AtmosphereScenes.await(session, 1)) {
                assertEquals(before, BackendConformance.copy(image));
            }
            var zero =
                    new FogRegion(
                            fog.center(),
                            fog.halfExtent(),
                            0,
                            fog.scattering(),
                            Vec3.ZERO,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0);
            session.suspend();
            var disabled =
                    new WorldSnapshot(
                            3,
                            open.instances(),
                            List.of(),
                            open.lights(),
                            Sky.BLACK.withFog(List.of(zero)));
            session.update(disabled, nav.view(), settings);
            try (var image = AtmosphereScenes.await(session, 1)) {
                assertEquals(0.0, sum(BackendConformance.copy(image)));
            }
        }
    }

    private static double sum(float[] values) {
        double sum = 0;
        for (float v : values) {
            assertTrue(Float.isFinite(v) && v >= 0);
            sum += v;
        }
        return sum;
    }
}
