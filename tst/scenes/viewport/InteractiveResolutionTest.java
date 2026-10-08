package engine;

import static harness.Assertions.*;

import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import scenes.viewport.*;

public class InteractiveResolutionTest {
    private static ViewportState state() {
        var s =
                new ViewportState(
                        new Rect(new Vec3(-.5f, -.5f, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)),
                        800,
                        500);
        ScenePresets.load(s, "glass");
        s.workers(1);
        return s;
    }

    private static void move(InteractiveResolution c, ViewportState s, long now) {
        s.eye(s.eye().add(new Vec3(.01f, 0, 0)));
        c.observe(s, now);
    }

    @Test
    void optInAspectBoundsHysteresisAndSettle() {
        var s = state();
        var c = new InteractiveResolution();
        long now = 1_000_000_000L;
        c.observe(s, now);
        move(c, s, now);
        c.choose(s, now);
        assertEquals(800, s.sampledWidth()); // off
        s.interactive(true);
        c.choose(s, now);
        assertEquals(400, s.sampledWidth());
        assertEquals(250, s.sampledHeight());
        var sensor = s.cameraSensor();
        var eye = s.eye();
        int depth = s.pathDepth();
        c.completed(400, 250, 80_000_000L);
        now += 100_000_000L;
        move(c, s, now);
        c.choose(s, now);
        assertEquals(400, s.sampledWidth()); // cooldown protects grid stability
        now += 500_000_000L;
        move(c, s, now);
        c.choose(s, now);
        assertEquals(200, s.sampledWidth());
        assertEquals(125, s.sampledHeight()); // default minimum
        assertEquals(sensor, s.cameraSensor());
        assertEquals(depth, s.pathDepth());
        assertNotEquals(eye, s.eye());
        s.paused(true);
        c.choose(s, now + 1_000_000_000L);
        assertEquals(200, s.sampledWidth());
        s.paused(false);
        c.choose(s, now + 1_000_000_000L);
        assertEquals(800, s.sampledWidth());
        assertEquals(500, s.sampledHeight());
        assertEquals(800, s.renderSnapshot().sensorPixelsW());
        s.interactiveMinimum(300, 200);
        move(c, s, now + 2_000_000_000L);
        c.choose(s, now + 2_000_000_000L);
        assertEquals(320, s.sampledWidth());
        assertEquals(200, s.sampledHeight());
        s.interactive(false);
        c.choose(s, now + 2_000_000_001L);
        assertEquals(800, s.sampledWidth());
        s.interactive(true);
        s.resolution(64, 100);
        move(c, s, now + 3_000_000_000L);
        c.choose(s, now + 3_000_000_000L);
        assertEquals(64, s.sampledWidth());
        assertEquals(100, s.sampledHeight());
    }

    @Test
    void growthRequiresNewMeasurementsAndDeadbandDoesNotOscillate() {
        var s = state();
        s.interactive(true);
        var c = new InteractiveResolution();
        long now = 1_000_000_000L;
        c.observe(s, now);
        move(c, s, now);
        c.choose(s, now); // half
        c.completed(400, 250, 10_000_000L);
        for (int i = 0; i < 100; i++) {
            now += 10_000_000L;
            move(c, s, now);
            c.choose(s, now);
        }
        assertEquals(400, s.sampledWidth()); // a repeated display cannot count as new feedback
        for (int i = 0; i < 8; i++) {
            c.completed(400, 250, 1_000_000L);
            now += 600_000_000L;
            move(c, s, now);
            c.choose(s, now);
        }
        assertTrue(s.sampledWidth() > 400);
        int width = s.sampledWidth();
        for (int i = 0; i < 20; i++) {
            now += 10_000_000L;
            move(c, s, now);
            c.choose(s, now);
        }
        assertEquals(width, s.sampledWidth());
    }

    @Test
    void commandHelpAndValidation() {
        var s = state();
        var cmd = new ViewportCommand(s, 1440, 900);
        assertTrue(cmd.run("view", "interactive", "on").isSuccess());
        assertTrue(s.interactive());
        assertTrue(cmd.run("view", "interactive", "target", "20").isSuccess());
        assertEquals(20., s.interactiveMillis());
        assertTrue(cmd.run("view", "interactive", "min", "100", "100").isSuccess());
        assertFalse(cmd.run("view", "interactive", "target", "NaN").isSuccess());
        assertFalse(cmd.run("view", "interactive", "min", "63", "100").isSuccess());
        assertFalse(cmd.run("view", "interactive", "on", "extra").isSuccess());
        assertTrue(cmd.help("interactive", "target").isSuccess());
        assertTrue(cmd.run("view", "resolution", "half").isSuccess());
        assertEquals(720, s.sensorPixelsW());
        assertEquals(450, s.sensorPixelsH());
        assertTrue(cmd.run("view", "interactive", "off").isSuccess());
        assertFalse(s.interactive());
    }

    @Test
    void movingPreviewsAreExactAndStationaryFullGridConverges() throws Exception {
        var s = state();
        s.resolution(256, 160);
        s.interactive(true);
        s.sampleTarget(2);
        s.samplesPerFrame(8);
        try (var async = new AsyncViewportTrace(s, new DirectRgbTracer(s))) {
            synchronized (s) {
                async.request();
            }
            int previews = 0, edits = 0;
            long last = -1, deadline = System.nanoTime() + 5_000_000_000L;
            AsyncViewportTrace.Image retained = null;
            while (previews < 5 && System.nanoTime() < deadline) {
                synchronized (s) {
                    s.eye(new Vec3(++edits * .001f, 0, -1));
                    async.invalidate();
                    async.request();
                    var image = async.image();
                    if (image != null
                            && image.samples() > 0
                            && image.requestedNanos() != last
                            && image.key().width() < 256) {
                        previews++;
                        last = image.requestedNanos();
                        if (retained == null) retained = async.retain(image);
                    }
                    async.presented(image);
                }
                Thread.sleep(3);
            }
            assertTrue(previews >= 5);
            assertNotNull(retained);
            var key = retained.key();
            var reference = s.renderSnapshot();
            reference.resolution(key.width(), key.height());
            reference.eye(key.eye());
            reference.cameraSensor(key.sensor());
            reference.samplesPerFrame(1);
            reference.sampleTarget(1);
            try (var tracer = new DirectRgbTracer(reference)) {
                assertEquals(tracer.trace(), retained.rgb());
            }
            synchronized (s) {
                async.release(retained);
            }
            AsyncViewportTrace.Image complete = null;
            deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                synchronized (s) {
                    async.request();
                    var image = async.image();
                    async.presented(image);
                    if (image != null
                            && image.key().equals(s.renderKey())
                            && image.key().width() == 256
                            && image.samples() == 2) {
                        complete = async.retain(image);
                        break;
                    }
                }
                Thread.sleep(3);
            }
            assertNotNull(complete);
            assertEquals(256, s.sensorPixelsW());
            assertEquals(160, s.sensorPixelsH());
            reference = s.renderSnapshot();
            reference.samplesPerFrame(1);
            try (var tracer = new DirectRgbTracer(reference)) {
                tracer.trace();
                assertEquals(tracer.trace(), complete.rgb());
            }
            synchronized (s) {
                async.release(complete);
            }
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void automaticModeHonorsExplicitEditsPauseAndOff() throws Exception {
        var s = state();
        s.interactive(true);
        s.resolution(512, 320);
        try (var async = new AsyncViewportTrace(s, new DirectRgbTracer(s))) {
            synchronized (s) {
                async.request();
                s.eye(s.eye().add(new Vec3(.1f, 0, 0)));
                async.invalidate();
                // Explicit dimensions/transport remain hard edits even with an automatic grid.
                s.resolution(80, 100);
                s.pathDepth(0);
                s.paused(true);
                async.invalidate();
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            AsyncViewportTrace.Image image = null;
            while (System.nanoTime() < deadline) {
                synchronized (s) {
                    async.request();
                    image = async.image();
                    if (image != null && image.key().equals(s.renderKey())) break;
                }
                Thread.sleep(2);
            }
            assertNotNull(image);
            assertEquals(80, image.key().width());
            assertEquals(100, image.key().height());
            assertEquals(0L, image.samples());
            assertEquals(0, image.key().depth());
            synchronized (s) {
                s.interactive(false);
                s.paused(false);
                s.sampleTarget(2);
                async.invalidate();
            }
            deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                synchronized (s) {
                    async.request();
                    image = async.image();
                    if (image != null && image.key().equals(s.renderKey()) && image.samples() == 2)
                        break;
                }
                Thread.sleep(2);
            }
            assertEquals(2L, image.samples());
            assertEquals(80, image.key().width());
            synchronized (s) {
                async.suspend();
            }
        }
    }
}
