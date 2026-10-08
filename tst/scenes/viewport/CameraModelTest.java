package engine;

import static harness.Assertions.*;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Ray;
import math.Vec3;

import scenes.viewport.*;

import java.util.List;

public class CameraModelTest {
    private static ViewportState state() {
        return TemporalReconstructionTest.plane();
    }

    private static void close(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError(expected + " != " + actual);
    }

    private static void command(ViewportCommand c, String text) {
        var r = c.run(text.split(" "));
        if (r.isFailure()) throw new AssertionError(r.getFailure());
    }

    private static Camera.RaySample ray(Camera camera, float u, float v, float a, float b) {
        var r = new Camera.RaySample();
        camera.compile().sample(u, v, a, b, r);
        return r;
    }

    @Test
    void controlsValidationNoOpsAndRememberedModes() {
        var s = state();
        var cmd = new ViewportCommand(s);
        var original = s.renderKey();
        command(cmd, "view camera focus distance 7");
        assertEquals(original, s.renderKey());
        command(cmd, "view camera mode orthographic");
        close(7, s.camera().height(), 1e-5);
        command(cmd, "view camera height 4");
        command(cmd, "view camera mode lens");
        command(cmd, "view camera aperture 0.2");
        var lens = s.camera();
        command(cmd, "view camera mode orthographic");
        close(4, s.camera().height(), 0);
        command(cmd, "view camera mode lens");
        assertEquals(lens, s.camera());
        var key = s.renderKey();
        command(cmd, "view camera aperture 0.2");
        command(cmd, "view camera mode lens");
        assertEquals(key, s.renderKey());
        for (String invalid :
                new String[] {
                    "view camera fov NaN",
                    "view camera fov 180",
                    "view camera height 3",
                    "view camera aperture -1",
                    "view camera aperture 1e30",
                    "view camera focus distance 1e30",
                    "view camera focus distance 0",
                    "view camera mode fish",
                    "view camera status extra"
                }) {
            assertTrue(cmd.run(invalid.split(" ")).isFailure());
            assertEquals(lens, s.camera());
        }
        command(cmd, "view camera aperture 0");
        command(cmd, "view camera mode perspective");
        command(cmd, "view camera fov 60");
        var plane = s.cameraSensor();
        var eye = s.eye();
        close(60, s.camera().fov(), 1e-4);
        s.resolution(80, 100);
        assertEquals(plane, s.cameraSensor());
        assertEquals(eye, s.eye());
        var same = s.renderKey();
        command(cmd, "view camera fov 60");
        assertEquals(same, s.renderKey());
        command(cmd, "view camera aperture 1");
        assertEquals(1f, s.camera().aperture());
        for (String[] path :
                new String[][] {
                    {"camera"},
                    {"camera", "mode"},
                    {"camera", "focus"},
                    {"camera", "focus", "center"}
                }) assertTrue(cmd.help(path).isSuccess());
    }

    @Test
    void resetPreservesSceneAndSpecialPresetViewpoint() {
        var s = state();
        ScenePresets.load(s, "glass-inside");
        var initial = s.camera();
        var instances = List.copyOf(s.instances());
        s.camera(s.camera().withMode("lens").withAperture(.2f).withFocus(2));
        s.eye(new Vec3(4, 2, -3));
        var cmd = new ViewportCommand(s);
        command(cmd, "view camera reset");
        assertEquals(initial, s.camera());
        assertEquals(instances, s.instances());
        assertEquals(64, s.sensorPixelsW());
        var key = s.renderKey();
        command(cmd, "view camera reset");
        assertEquals(key, s.renderKey());
    }

    @Test
    void legacyPlaneRaysAndConversionRejection() {
        var e = new Vec3(.1f, .2f, -1);
        var p = new Rect(new Vec3(-.4f, -.6f, 0), new Vec3(1, .1f, .02f), new Vec3(.15f, 1, .03f));
        var c = new Camera(e, p);
        var r = ray(c, .2f, .7f, 0, 0);
        float dx = p.origin().x() + p.edge1().x() * .2f + p.edge2().x() * .7f - e.x();
        float dy = p.origin().y() + p.edge1().y() * .2f + p.edge2().y() * .7f - e.y();
        float dz = p.origin().z() + p.edge1().z() * .2f + p.edge2().z() * .7f - e.z();
        float inv = 1 / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertEquals(dx * inv, r.dx);
        assertEquals(dy * inv, r.dy);
        assertEquals(dz * inv, r.dz);
        try {
            c.withMode("orthographic");
            throw new AssertionError("Conversion should reject skew");
        } catch (IllegalArgumentException expected) {
        }
        try {
            c.withMode("lens");
            throw new AssertionError("Conversion should reject skew");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void orthographicScaleOriginsForwardMovementAndNavigation() {
        var s = state();
        var c = s.camera();
        var p = ray(c, .8f, .2f, 0, 0);
        s.camera(c.withMode("orthographic"));
        var a = ray(s.camera(), .8f, .2f, 0, 0);
        var b = ray(s.camera(), .1f, .9f, 0, 0);
        assertEquals(a.dx, b.dx);
        assertEquals(a.dy, b.dy);
        assertEquals(a.dz, b.dz);
        close(a.ox, p.ox + p.dx * 5 / p.dz, 1e-6);
        close(a.oy, p.oy + p.dy * 5 / p.dz, 1e-6);
        assertEquals(c.eye().z(), a.oz);
        var projection = new CameraProjection(s.renderKey());
        int first = projection.project(a.ox, a.oy, 3), second = projection.project(a.ox, a.oy, 9);
        assertEquals(first, second);
        close(projection.footprint(2), projection.footprint(20), 0);
        var controls = new CameraControls();
        controls.press(1, 1, "camera.move.forward", 1_000_000_000L);
        controls.update(s, 1_100_000_000L);
        var moved = ray(s.camera(), .8f, .2f, 0, 0);
        close(a.ox, moved.ox, 0);
        close(a.oy, moved.oy, 0);
        close(a.oz + .3, moved.oz, 1e-6);
        assertEquals(5f, s.camera().height());
        controls.startLook(0, 0);
        controls.drag(10, 5);
        controls.update(s, 1_100_000_001L);
        close(5, s.camera().height(), 0);
        close(5, s.camera().focus(), 0);
        assertNotEquals(c.forward(), s.camera().forward());
    }

    @Test
    void orthographicProjectionRoundTripAndTemporalReuse() {
        var s = state();
        s.camera(s.camera().withMode("orthographic").withHeight(4));
        s.temporal(true);
        var projection = new CameraProjection(s.renderKey());
        for (int y = 1; y < 64; y += 7)
            for (int x = 1; x < 64; x += 7) {
                projection.point(x, y, 6);
                assertEquals(
                        y * 64 + x,
                        projection.project(projection.px, projection.py, projection.pz));
                close(5, projection.pz, 1e-6);
            }
        assertEquals(-1, projection.project(0, 0, -2));
        var history = new TemporalReconstruction();
        try (var t = new DirectRgbTracer(s)) {
            var raw = t.trace();
            history.reconstruct(raw, t.surfaceGuide(), s.renderKey(), 1, 1_000_000_000L);
            TemporalReconstructionTest.move(s, .02f);
            raw = t.trace();
            history.reconstruct(raw, t.surfaceGuide(), s.renderKey(), 1, 1_010_000_000L);
            assertTrue(history.stats().reused() > 3000);
            s.camera(s.camera().withHeight(5));
            raw = t.trace();
            history.reconstruct(raw, t.surfaceGuide(), s.renderKey(), 1, 1_020_000_000L);
            assertEquals(0, history.stats().reused());
            assertTrue(new CameraProjection(s.renderKey()).cut(projection));
            // Rotated orthographic origins can have tiny float plane residuals: those are not
            // focal-length cuts.
            var controls = new CameraControls();
            controls.startLook(0, 0);
            controls.drag(50, -30);
            controls.update(s, 1);
            projection = new CameraProjection(s.renderKey());
            for (int y = 1; y < 64; y += 9)
                for (int x = 1; x < 64; x += 9) {
                    projection.point(x, y, 6);
                    assertEquals(
                            y * 64 + x,
                            projection.project(projection.px, projection.py, projection.pz));
                }
            raw = t.trace();
            history.reconstruct(raw, t.surfaceGuide(), s.renderKey(), 1, 1_030_000_000L);
            TemporalReconstructionTest.move(s, .02f);
            assertFalse(new CameraProjection(s.renderKey()).cut(projection));
            raw = t.trace();
            history.reconstruct(raw, t.surfaceGuide(), s.renderKey(), 1, 1_040_000_000L);
            assertTrue(history.stats().reused() > 3000);
        }
    }

    @Test
    void thinLensFocusConvergenceDiskStatisticsAndDefocus() {
        var c = state().camera().withMode("lens").withFocus(6).withAperture(.4f);
        var sampler = new DirectRgbTracer.Sampler();
        sampler.reset(1, 0, 0);
        var reference = ray(c, .7f, .3f, 0, 0);
        double fx = reference.ox + reference.dx * 6 / reference.dz,
                fy = reference.oy + reference.dy * 6 / reference.dz;
        double radiusSq = 0, meanX = 0, meanY = 0, spread = 0;
        var compiled = c.compile();
        var r = new Camera.RaySample();
        for (int i = 0; i < 20000; i++) {
            compiled.sample(.7f, .3f, sampler.next(), sampler.next(), r);
            double t = 6 / r.dz;
            close(fx, r.ox + r.dx * t, 2e-6);
            close(fy, r.oy + r.dy * t, 2e-6);
            double x = r.ox - c.eye().x(), y = r.oy - c.eye().y();
            radiusSq += x * x + y * y;
            meanX += x;
            meanY += y;
            assertTrue(x * x + y * y <= .160001);
            double near = r.ox + r.dx * 3 / r.dz;
            spread += (near - fx * .5) * (near - fx * .5);
        }
        close(.08, radiusSq / 20000, .002);
        close(0, meanX / 20000, .005);
        close(0, meanY / 20000, .005);
        assertTrue(spread / 20000 > .005);
        var small = ray(c.withAperture(.1f), .7f, .3f, .7f, .3f);
        var large = ray(c, .7f, .3f, .7f, .3f);
        double defocusSmall = small.ox + small.dx * 3 / small.dz - fx * .5,
                defocusLarge = large.ox + large.dx * 3 / large.dz - fx * .5;
        close(4, defocusLarge / defocusSmall, 1e-4);
    }

    private static float[][][] completed(ViewportState s) {
        try (var t = new DirectRgbTracer(s)) {
            do {
                t.trace();
            } while (s.accumulatedSamples() < s.sampleTarget());
            return TemporalReconstructionTest.copy(t.radianceBuffer());
        }
    }

    @Test
    void zeroApertureExactAndNewModesIndependentOfWorkersBatchesAndCancellation() {
        for (String mode : new String[] {"lens", "orthographic"}) {
            var a = state();
            var b = state();
            a.sampleTarget(3);
            b.sampleTarget(3);
            a.samplesPerFrame(1);
            b.samplesPerFrame(3);
            b.workers(Math.min(4, Runtime.getRuntime().availableProcessors()));
            b.tileSize(8);
            a.camera(a.camera().withMode(mode));
            b.camera(b.camera().withMode(mode));
            if (mode.equals("lens")) {
                a.camera(a.camera().withAperture(.3f));
                b.camera(b.camera().withAperture(.3f));
            }
            assertEquals(completed(a), completed(b));
            var fresh = a.renderSnapshot();
            fresh.sampleTarget(3);
            try (var t = new DirectRgbTracer(fresh)) {
                t.trace();
                var count = new java.util.concurrent.atomic.AtomicInteger();
                t.trace(fresh, () -> count.incrementAndGet() > 2);
                assertTrue(t.cancelled);
                while (fresh.accumulatedSamples() < 3) t.trace();
                assertEquals(completed(b), t.radianceBuffer());
            }
        }
        var a = state();
        var b = state();
        a.sampleTarget(3);
        b.sampleTarget(3);
        b.camera(b.camera().withMode("lens"));
        assertEquals(a.renderKey(), b.renderKey());
        assertEquals(completed(a), completed(b));
    }

    @Test
    void finiteApertureDepthZeroBrightnessAndOriginMedia() {
        var s = state();
        s.instances().clear();
        s.lights().clear();
        s.pathDepth(0);
        s.sampleTarget(4);
        s.instances()
                .add(
                        new SceneInstance(
                                "emitter",
                                PolygonMesh.parallelogram(
                                        new Vec3(-20, -20, 5),
                                        new Vec3(0, 40, 0),
                                        new Vec3(40, 0, 0)),
                                Transform.IDENTITY,
                                new Material("emitter", Vec3.ZERO)
                                        .withEmission(new Vec3(2, 2, 2))));
        var pinhole = completed(s);
        s.camera(s.camera().withMode("lens").withAperture(.4f));
        assertEquals(pinhole, completed(s));
        // A closed absorbing sphere crosses the origins: center inside, corners outside.
        s.pathDepth(8);
        s.sampleTarget(1);
        s.instances()
                .add(
                        new SceneInstance(
                                "medium",
                                new AnalyticSphere(new Vec3(0, 0, -1), .5f),
                                Transform.IDENTITY,
                                new Material(
                                        "medium",
                                        new Vec3(1, 1, 1),
                                        Material.Kind.DIELECTRIC,
                                        1,
                                        new Vec3(1, 1, 1))));
        s.instances()
                .add(
                        new SceneInstance(
                                "inner-medium",
                                new AnalyticSphere(new Vec3(0, 0, -1), .25f),
                                Transform.IDENTITY,
                                new Material(
                                        "inner-medium",
                                        new Vec3(1, 1, 1),
                                        Material.Kind.DIELECTRIC,
                                        1,
                                        new Vec3(2, .5f, 1))));
        for (String mode : new String[] {"orthographic", "lens"}) {
            s.camera(s.camera().withMode(mode));
            if (mode.equals("orthographic")) s.camera(s.camera().withHeight(2));
            else s.camera(s.camera().withAperture(1));
            try (var t = new DirectRgbTracer(s)) {
                var image = TemporalReconstructionTest.copy(t.trace());
                var camera = s.camera().compile();
                var sampler = new DirectRgbTracer.Sampler();
                var aperture = new DirectRgbTracer.Sampler();
                long seed = DirectRgbTracer.cameraSeed(s.seed(), s.camera().identity());
                int inside = 0, outside = 0;
                for (int y : new int[] {4, 24, 32, 54})
                    for (int x : new int[] {3, 20, 33, 60}) {
                        long pixel = y * 64 + x;
                        sampler.reset(seed, pixel, 0);
                        aperture.reset(seed ^ 0x4150455254555245L, pixel, 0);
                        var r = new Camera.RaySample();
                        camera.sample(
                                (x + sampler.next()) / 64,
                                (y + sampler.next()) / 64,
                                aperture.next(),
                                aperture.next(),
                                r);
                        if (r.ox * r.ox + r.oy * r.oy < .25f) inside++;
                        else outside++;
                        var exact =
                                t.radiance(
                                        new Ray(
                                                new Vec3(r.ox, r.oy, r.oz),
                                                new Vec3(r.dx, r.dy, r.dz)),
                                        0);
                        for (int channel = 0; channel < 3; channel++)
                            close(exact[channel], image[channel][y][x], 2e-5);
                    }
                assertTrue(inside > 0);
                assertTrue(outside > 0);
            }
        }
    }

    @Test
    void depthZeroStillSamplesApertureAndFocusCenterUsesFirstSurface() {
        var s = state();
        s.pathDepth(0);
        s.camera(s.camera().withMode("lens").withAperture(.5f).withFocus(3));
        try (var t = new DirectRgbTracer(s)) {
            var first = TemporalReconstructionTest.copy(t.trace());
            assertNotEquals(first, t.trace());
        }
        var cmd = new ViewportCommand(s);
        command(cmd, "view camera focus center");
        close(6, s.camera().focus(), 1e-6);
        var captured = s.renderSnapshot();
        s.eye(s.eye().add(new Vec3(.1f, 0, 0)));
        assertTrue(cmd.publishFocus(captured, 2).isFailure());
        close(6, s.camera().focus(), 0);
        s.instances().clear();
        var prior = s.camera();
        assertTrue(cmd.run("view", "camera", "focus", "center").isFailure());
        assertEquals(prior, s.camera());
        ScenePresets.load(s, "glass");
        s.instances()
                .add(
                        new SceneInstance(
                                "focus-glass",
                                new AnalyticSphere(new Vec3(0, 0, 2), .4f),
                                Transform.IDENTITY,
                                new Material("focus-glass", new Vec3(1, 1, 1))
                                        .withKind(Material.Kind.DIELECTRIC)));
        command(cmd, "view camera focus center");
        close(2.6, s.camera().focus(), 1e-5);
        // Off-axis legacy reference: axial distance must differ from slanted ray distance.
        s = state();
        var p = s.cameraSensor();
        s.cameraSensor(new Rect(p.origin().add(new Vec3(1, 0, 0)), p.edge1(), p.edge2()));
        cmd = new ViewportCommand(s);
        command(cmd, "view camera focus center");
        close(6, s.camera().focus(), 1e-5);
    }

    @Test
    void effectiveTemporalBudgetAndMotionIdentity() {
        var s = state();
        s.temporal(true);
        s.temporalBudget(true);
        s.motionScale(.25);
        s.samplesPerFrame(4);
        var resolution = new InteractiveResolution();
        long now = 1_000_000_000L;
        resolution.observe(s, now);
        s.camera(s.camera().withMode("lens").withAperture(.2f));
        resolution.observe(s, now + 1);
        assertTrue(resolution.moving(now + 1));
        assertTrue(s.temporal());
        assertFalse(s.temporalEffective());
        assertFalse(s.temporalBudgetSupported());
        assertEquals(4, s.movingBatch(true));
        assertTrue(s.temporalStatus().contains("finite aperture"));
        try (var t = new DirectRgbTracer(s)) {
            t.trace();
            assertNull(t.surfaceGuide());
        }
        s.camera(s.camera().withAperture(0));
        assertTrue(s.temporalEffective());
        assertTrue(s.temporalBudgetSupported());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
