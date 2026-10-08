package profiling;

import static harness.Assertions.*;

import harness.SuiteRunner;
import harness.Test;

import rendering.Color;
import rendering.PixelRaster;

import java.awt.Canvas;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

public class FrameProfilerTest {
    private long time;

    private FrameProfiler profiler() {
        return new FrameProfiler(() -> time);
    }

    @Test
    void nestedStagesAreExclusiveAndIdleClosesAtNextFrame() {
        var p = profiler();
        p.beginFrame();
        p.measure(
                FrameProfiler.Stage.SCENE,
                () -> {
                    time += 2_000_000;
                    p.measure(
                            FrameProfiler.Stage.TRACE,
                            () -> {
                                time += 5_000_000;
                            });
                    time += 3_000_000;
                });
        time += 1_000_000;
        p.endFrame();
        time += 9_000_000;
        p.beginFrame();
        var f = p.snapshot().frames().getFirst();
        assertEquals(5_000_000L, f.stage(FrameProfiler.Stage.SCENE));
        assertEquals(5_000_000L, f.stage(FrameProfiler.Stage.TRACE));
        assertEquals(1_000_000L, f.stage(FrameProfiler.Stage.OTHER));
        assertEquals(9_000_000L, f.stage(FrameProfiler.Stage.IDLE));
        assertEquals(f.duration(), f.nanos().stream().mapToLong(Long::longValue).sum());
        assertEquals(50., p.snapshot().fps());
    }

    @Test
    void percentilesAndRayStatsDoNotLeakAcrossScenes() {
        var p = profiler();
        p.beginFrame();
        for (int i = 1; i <= 20; i++) {
            if (i == 1) p.rayStats(new FrameProfiler.Rays(10, 10, 100, 1, 1, 0, 1, 100));
            if (i == 1) p.traceStats(new TraceProfile.Stats(50, 60, 100, 1));
            time += i * 1_000_000L;
            p.endFrame();
            p.beginFrame();
        }
        assertEquals(10.5, p.snapshot().meanMs());
        assertEquals(19., p.snapshot().p95Ms());
        assertNotNull(p.snapshot().frames().getFirst().rays());
        assertNull(p.snapshot().frames().getLast().rays());
        assertNotNull(p.snapshot().frames().getFirst().trace());
        assertNull(p.snapshot().frames().getLast().trace());
    }

    @Test
    void historyIsTimeBoundedAndCapacityBounded() {
        var p = profiler();
        p.beginFrame();
        for (int i = 0; i < 5000; i++) {
            time += 1;
            p.endFrame();
            p.beginFrame();
        }
        assertEquals(4096, p.snapshot().frames().size());
        time += 11_000_000_000L;
        p.endFrame();
        p.beginFrame();
        assertEquals(1, p.snapshot().frames().size());
    }

    @Test
    void freshHistoryIsBoundedEvenWithVeryFastPublications() {
        try (var p = profiler()) {
            for (int i = 0; i < 5000; i++) {
                p.beginFrame();
                p.viewportImage(i, time, 64, 64, "triangle", "converging");
                time++;
                p.endFrame();
            }
            assertEquals(4096, p.imageSnapshot().updates().size());
            time += 11_000_000_000L;
            p.beginFrame();
            p.viewportImage(4999, 4999, 64, 64, "triangle", "complete");
            p.endFrame();
            assertEquals(0, p.imageSnapshot().updates().size());
            assertEquals(0., p.imageSnapshot().fps());
        }
    }

    @Test
    void exceptionalScopeStillUnwinds() {
        var p = profiler();
        p.beginFrame();
        try {
            p.measure(
                    FrameProfiler.Stage.TRACE,
                    () -> {
                        time += 10;
                        throw new IllegalStateException("expected");
                    });
        } catch (IllegalStateException expected) {
        }
        p.measure(
                FrameProfiler.Stage.PAINT,
                () -> {
                    time += 20;
                });
        p.endFrame();
        p.beginFrame();
        assertEquals(10L, p.snapshot().frames().getFirst().stage(FrameProfiler.Stage.TRACE));
        assertEquals(20L, p.snapshot().frames().getFirst().stage(FrameProfiler.Stage.PAINT));
    }

    @Test
    void freshImagesIgnoreRedisplaysAndIncludePresentationAndStalls() {
        try (var p = profiler()) {
            for (int frame = 0; frame < 200; frame++) {
                p.beginFrame();
                // 100 display updates/s, but only 10 new image publications/s.
                long id = frame / 10;
                p.viewportImage(id, id * 100_000_000L, 1440, 900, "glass", "converging");
                time += 10_000_000;
                p.endFrame();
            }
            var images = p.imageSnapshot();
            assertEquals(10., images.fps());
            assertEquals(20, images.updates().size());
            assertEquals(100., images.p95Ms());
            assertEquals(90., images.holdMs());
            assertEquals(100., images.ageMs());
            assertEquals(10., images.ageP95Ms());
            // No new image: a long hold must reduce the rate and appear in p95, not vanish.
            time += 3_000_000_000L;
            p.beginFrame();
            p.viewportImage(19, 1_900_000_000L, 1440, 900, "glass", "paused");
            p.endFrame();
            images = p.imageSnapshot();
            assertEquals(0., images.fps());
            assertEquals(3090., images.p95Ms());
            assertEquals("paused", images.status());
            // Dimension changes reset the measurement window without counting the old image again.
            p.beginFrame();
            p.viewportImage(19, 1_900_000_000L, 720, 450, "glass", "converging");
            p.endFrame();
            assertEquals(0, p.imageSnapshot().updates().size());
            time += 500_000_000L;
            assertEquals(0., p.imageSnapshot().fps());
            p.beginFrame();
            p.viewportImage(20, time - 20_000_000L, 720, 450, "glass", "converging");
            time += 5_000_000; // includes the last presentation stage, after viewport conversion
            p.endFrame();
            assertEquals(25., p.imageSnapshot().ageP95Ms());
            p.beginFrame();
            p.endFrame(); // editor scene
            assertEquals(0, p.imageSnapshot().updates().size());
            assertEquals(-1., p.imageSnapshot().ageMs());
        }
    }

    @Test
    void toggleDebouncesKeyRepeatAndPassesOtherKeys() {
        var p = profiler();
        int[] forwarded = {0};
        var listener =
                new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent event) {
                        forwarded[0]++;
                    }
                };
        var input = new ProfilingInput(p, listener);
        var source = new Canvas();
        var f3 =
                new KeyEvent(
                        source,
                        KeyEvent.KEY_PRESSED,
                        0,
                        0,
                        KeyEvent.VK_F3,
                        KeyEvent.CHAR_UNDEFINED);
        assertFalse(p.visible());
        input.keyPressed(f3);
        input.keyPressed(f3);
        assertTrue(p.visible());
        input.keyReleased(
                new KeyEvent(
                        source,
                        KeyEvent.KEY_RELEASED,
                        0,
                        0,
                        KeyEvent.VK_F3,
                        KeyEvent.CHAR_UNDEFINED));
        input.keyPressed(f3);
        assertFalse(p.visible());
        input.keyPressed(new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_W, 'w'));
        assertEquals(1, forwarded[0]);
        var f4 =
                new KeyEvent(
                        source,
                        KeyEvent.KEY_PRESSED,
                        0,
                        0,
                        KeyEvent.VK_F4,
                        KeyEvent.CHAR_UNDEFINED);
        input.keyPressed(f4);
        input.keyPressed(f4);
        assertTrue(p.visible());
        assertTrue(p.traceDetails());
        input.keyReleased(
                new KeyEvent(
                        source,
                        KeyEvent.KEY_RELEASED,
                        0,
                        0,
                        KeyEvent.VK_F4,
                        KeyEvent.CHAR_UNDEFINED));
        input.keyPressed(f4);
        assertFalse(p.traceDetails());
        assertEquals(1, forwarded[0]);
    }

    @Test
    void overlayIsHiddenByDefaultAndRendersBothScenes() throws Exception {
        var sampler = new TraceSampler();
        var p = new FrameProfiler(() -> time, sampler);
        var raster = new PixelRaster(800, 800, Color.NamedColor.BLACK);
        var overlay = new PerformanceOverlay(p, raster, 144);
        overlay.render();
        assertEquals(Color.NamedColor.BLACK.rgbInt24(), raster.pixel(12, 12).rgbInt24());
        p.beginFrame();
        for (int i = 0; i < 400; i++) {
            final int frame = i;
            p.measure(
                    FrameProfiler.Stage.TRACE,
                    () -> {
                        time += frame % 50 == 0 ? 28_000_000 : 8_000_000;
                    });
            p.measure(
                    FrameProfiler.Stage.RESAMPLE,
                    () -> {
                        time += 2_000_000;
                    });
            p.measure(
                    FrameProfiler.Stage.PAINT,
                    () -> {
                        time += 3_000_000;
                    });
            p.measure(
                    FrameProfiler.Stage.PRESENT,
                    () -> {
                        time += 1_000_000;
                    });
            p.rayStats(
                    new FrameProfiler.Rays(
                            1600, 1600, 2_560_000, 210_000, 210_000, 12_000, 198_000, 8_000_000));
            p.traceStats(new TraceProfile.Stats(7_000_000, 100_000_000, 2_560_000, 210_000));
            p.endFrame();
            time += 1_000_000;
            p.beginFrame();
        }
        p.toggle();
        overlay.render();
        assertNotEquals(Color.NamedColor.BLACK.rgbInt24(), raster.pixel(12, 12).rgbInt24());
        save(raster, "out/cli/perf-viewport.png");
        p.renderProgress(
                new FrameProfiler.RenderProgress(
                        100, 99, 4, 40_000_000, 127_000_000, 81, 6_804_480, 29_300_000, true));
        long onset = time;
        for (int i = 0; i < 300; i++) {
            p.viewportImage(
                    i / 10,
                    onset + (i / 10) * 100_000_000L - 90_000_000L,
                    1440,
                    900,
                    "glass",
                    "converging");
            p.rayStats(
                    new FrameProfiler.Rays(
                            1440, 900, 1_296_000, 210_000, 210_000, 12_000, 198_000, 90_000_000));
            p.traceStats(new TraceProfile.Stats(50_000_000, 8192, 2_560_000, 210_000));
            p.measure(
                    FrameProfiler.Stage.PAINT,
                    () -> {
                        time += 1_000_000;
                    });
            time += 9_000_000;
            p.endFrame();
            p.beginFrame();
        }
        p.renderProgress(
                new FrameProfiler.RenderProgress(
                        100, 99, 4, 40_000_000, 127_000_000, 81, 6_804_480, 29_300_000, true));
        time += 300_000_000;
        overlay.render();
        save(raster, "out/cli/perf-viewport-async.png");
        for (int i = 0; i < 200; i++)
            sampler.record(System.nanoTime(), TraceSampler.Work.values()[i % 4]);
        p.toggleTraceDetails();
        overlay.render();
        save(raster, "out/cli/perf-trace.png");
        p.toggleTraceDetails();
        time += 300_000_000;
        p.endFrame();
        p.beginFrame();
        overlay.render();
        assertNull(p.renderProgress());
        save(raster, "out/cli/perf-editor.png");
        p.toggle();
        raster.write((_, _, _) -> Color.NamedColor.BLACK);
        overlay.render();
        assertEquals(Color.NamedColor.BLACK.rgbInt24(), raster.pixel(12, 12).rgbInt24());
    }

    @Test
    void samplesAreExclusiveBoundedAndUnsupportedMetricsStayUnavailable() {
        String prefix = "engine.BackwardRayTracer.";
        assertEquals(
                TraceSampler.Work.SHADOW,
                TraceSampler.classify(
                        java.util.List.of(
                                prefix + "occluded", prefix + "light", prefix + "trace")));
        assertEquals(
                TraceSampler.Work.INTERSECTION,
                TraceSampler.classify(
                        java.util.List.of(
                                prefix + "nearestHit", prefix + "shade", prefix + "trace")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(prefix + "light", prefix + "trace")));
        assertEquals(
                TraceSampler.Work.GENERATION,
                TraceSampler.classify(java.util.List.of(prefix + "trace")));
        assertNull(TraceSampler.classify(java.util.List.of("other.Work.render")));
        String worker = "engine.DirectRgbTracer$Worker.";
        assertEquals(
                TraceSampler.Work.SHADOW,
                TraceSampler.classify(
                        java.util.List.of(
                                worker + "volumeVisibility",
                                worker + "nearestHit",
                                worker + "call")));
        assertEquals(
                TraceSampler.Work.INTERSECTION,
                TraceSampler.classify(java.util.List.of(worker + "nearestHit", worker + "call")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(worker + "areaLight", worker + "call")));
        assertEquals(
                TraceSampler.Work.GENERATION,
                TraceSampler.classify(java.util.List.of(worker + "render", worker + "call")));
        String rgb = "engine.DirectRgbTracer.";
        assertEquals(
                TraceSampler.Work.SHADOW,
                TraceSampler.classify(
                        java.util.List.of(rgb + "occluded", rgb + "light", rgb + "trace")));
        assertEquals(
                TraceSampler.Work.INTERSECTION,
                TraceSampler.classify(java.util.List.of(rgb + "nearestHit", rgb + "trace")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(rgb + "light", rgb + "trace")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(rgb + "areaLight", rgb + "trace")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(rgb + "roughPointLight", rgb + "trace")));
        assertEquals(
                TraceSampler.Work.SHADOW,
                TraceSampler.classify(
                        java.util.List.of(
                                rgb + "nearestHit",
                                rgb + "volumeVisibility",
                                rgb + "volumeLight",
                                rgb + "trace")));
        assertEquals(
                TraceSampler.Work.LIGHTING,
                TraceSampler.classify(java.util.List.of(rgb + "volumeLight", rgb + "trace")));
        var sampler = new TraceSampler();
        for (int i = 0; i < 5000; i++) sampler.record(i, TraceSampler.Work.SHADOW);
        assertEquals(4096L, sampler.snapshot(5000).total());
        assertEquals(0L, sampler.snapshot(11_000_000_000L).total());
        sampler.close();
        assertEquals(-1L, RuntimeMetrics.delta(-1, 100));
        assertEquals(-1L, RuntimeMetrics.delta(100, -1));
        assertEquals(50L, RuntimeMetrics.delta(100, 150));
    }

    private static void save(PixelRaster raster, String path) throws Exception {
        var image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        image.getRaster().setPixels(0, 0, 800, 800, raster.rgb());
        ImageIO.write(image, "png", new File(path));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
