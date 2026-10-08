package engine;

import static harness.Assertions.*;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import scenes.viewport.*;

import java.util.HashMap;

public class CameraLifecycleTest {
    private static AsyncViewportTrace.Image await(
            AsyncViewportTrace async, ViewportState s, int samples) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (s) {
                async.request();
                var image = async.image();
                if (image != null
                        && image.key().equals(s.renderKey())
                        && image.samples() >= samples
                        && image.cameraHistoryVersion() == s.cameraHistoryVersion())
                    return async.retain(image);
            }
            Thread.sleep(1);
        }
        throw new AssertionError("No current camera image");
    }

    @Test
    void modeApertureTransitionsNeverReviveOldHistoryAndKeepRequestedSettings() throws Exception {
        var s = TemporalReconstructionTest.plane();
        s.temporal(true);
        s.temporalBudget(true);
        s.sampleTarget(1);
        try (var async = new AsyncViewportTrace(s, new DirectRgbTracer(s))) {
            var first = await(async, s, 1);
            assertNotNull(first.reconstructed());
            synchronized (s) {
                s.camera(s.camera().withMode("lens").withAperture(.3f));
                async.invalidate();
                s.camera(s.camera().withAperture(0));
                async.invalidate();
            }
            var restored = await(async, s, 1);
            assertNotSame(first, restored);
            assertEquals(0, restored.history().reused());
            assertEquals(first.rgb(), restored.rgb());
            synchronized (s) {
                async.release(first);
                async.release(restored);
                s.camera(s.camera().withAperture(.3f));
                async.invalidate();
            }
            var lens = await(async, s, 1);
            assertNull(lens.reconstructed());
            assertFalse(lens.motionBudget());
            assertTrue(s.temporal());
            synchronized (s) {
                async.release(lens);
                s.camera(s.camera().withMode("orthographic"));
                async.invalidate();
            }
            var ortho = await(async, s, 1);
            assertNotNull(ortho.reconstructed());
            assertEquals(0, ortho.history().reused());
            synchronized (s) {
                long revision = s.cameraHistoryVersion();
                s.camera(s.camera().withAperture(.2f));
                assertTrue(s.cameraHistoryVersion() > revision);
                s.camera(s.camera().withAperture(0));
                async.invalidate();
            }
            var restoredOrtho = await(async, s, 1);
            assertNotSame(ortho, restoredOrtho);
            assertEquals(0, restoredOrtho.history().reused());
            synchronized (s) {
                async.release(restoredOrtho);
            }
            synchronized (s) {
                async.release(ortho);
            }
        }
    }

    @Test
    void sustainedNewCameraMotionPublishesExactCapturedPreviewsAndSettles() throws Exception {
        for (String mode : new String[] {"orthographic", "lens", "orthographic-aperture"}) {
            var s = TemporalReconstructionTest.plane();
            s.resolution(256);
            s.samplesPerFrame(8);
            s.tileSize(8);
            s.sampleTarget(3);
            s.camera(
                    s.camera()
                            .withMode(
                                    mode.equals("orthographic-aperture") ? "orthographic" : mode));
            if (!mode.equals("orthographic")) s.camera(s.camera().withAperture(.2f));
            var captured = new HashMap<ViewportState.RenderKey, ViewportState>();
            int previews = 0, edits = 0;
            long last = -1, deadline = System.nanoTime() + 5_000_000_000L;
            try (var async = new AsyncViewportTrace(s, new DirectRgbTracer(s))) {
                while (previews < 3 && System.nanoTime() < deadline) {
                    AsyncViewportTrace.Image image;
                    synchronized (s) {
                        var camera = s.camera();
                        var delta = new Vec3(.001f, 0, 0);
                        s.camera(
                                camera.withPose(
                                        camera.eye().add(delta),
                                        new engine.objects.Rect(
                                                camera.sensor().origin().add(delta),
                                                camera.sensor().edge1(),
                                                camera.sensor().edge2())));
                        captured.put(s.renderKey(), s.renderSnapshot());
                        async.request();
                        camera = s.camera();
                        s.camera(
                                camera.withPose(
                                        camera.eye().add(delta),
                                        new engine.objects.Rect(
                                                camera.sensor().origin().add(delta),
                                                camera.sensor().edge1(),
                                                camera.sensor().edge2())));
                        async.invalidate();
                        edits++;
                        image = async.acquireImage();
                    }
                    if (image != null)
                        try {
                            if (image.requestedNanos() != last
                                    && !image.key().equals(s.renderKey())) {
                                var snapshot = captured.get(image.key());
                                assertNotNull(snapshot);
                                snapshot.sampleTarget(image.samples());
                                try (var reference = new DirectRgbTracer(snapshot)) {
                                    do {
                                        reference.trace();
                                    } while (snapshot.accumulatedSamples() < image.samples());
                                    assertEquals(reference.radianceBuffer(), image.rgb());
                                }
                                last = image.requestedNanos();
                                previews++;
                            }
                        } finally {
                            synchronized (s) {
                                async.release(image);
                            }
                        }
                    Thread.sleep(1);
                }
                assertTrue(edits > 3);
                assertEquals(3, previews);
                var settled = await(async, s, 3);
                assertEquals(3L, s.accumulatedSamples());
                var snapshot = s.renderSnapshot();
                snapshot.sampleTarget(3);
                try (var reference = new DirectRgbTracer(snapshot)) {
                    do {
                        reference.trace();
                    } while (snapshot.accumulatedSamples() < 3);
                    assertEquals(reference.radianceBuffer(), settled.rgb());
                }
                synchronized (s) {
                    async.release(settled);
                }
            }
        }
    }

    @Test
    void adaptiveGridPreservesOpticsAndFiniteApertureRestoresTemporalBudgetGrid() {
        for (String mode : new String[] {"orthographic", "lens"}) {
            var s = TemporalReconstructionTest.plane();
            s.resolution(800, 500);
            s.camera(s.camera().withMode(mode));
            if (mode.equals("lens")) s.camera(s.camera().withAperture(.2f));
            s.interactive(true);
            var policy = new InteractiveResolution();
            long now = 1_000_000_000L;
            policy.observe(s, now);
            s.eye(s.eye().add(new Vec3(.01f, 0, 0)));
            var camera = s.camera();
            policy.observe(s, now + 1);
            policy.choose(s, now + 1);
            assertEquals(400, s.sampledWidth());
            assertEquals(camera, s.camera());
            policy.choose(s, now + 400_000_000L);
            assertEquals(800, s.sampledWidth());
            assertEquals(camera, s.camera());
        }
        var s = TemporalReconstructionTest.plane();
        s.resolution(800, 500);
        s.temporal(true);
        s.temporalBudget(true);
        s.motionScale(.25);
        var policy = new InteractiveResolution();
        long now = 1_000_000_000L;
        policy.observe(s, now);
        s.eye(s.eye().add(new Vec3(.01f, 0, 0)));
        policy.observe(s, now + 1);
        policy.choose(s, now + 1);
        assertEquals(200, s.sampledWidth());
        // Restore a centered plane before switching optics.
        s.eye(new Vec3(0, 0, -1));
        s.camera(s.camera().withMode("lens").withAperture(.2f));
        policy.observe(s, now + 2);
        policy.choose(s, now + 2);
        assertEquals(800, s.sampledWidth());
        assertTrue(s.temporalBudget());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
