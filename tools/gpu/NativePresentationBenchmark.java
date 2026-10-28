package game;

import gpu.GpuRenderSession;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;

/**
 * Matched ruins route: Swing offscreen versus an invisible native 4K swapchain. No scanout
 * measurement.
 */
public final class NativePresentationBenchmark {
    public static void main(String[] args) throws Exception {
        System.setProperty("game.nativePresentation", "true");
        System.out.println(
                "round,pacing,freshPaintsPerSecond,medianIntervalMs,p95IntervalMs,p95CopyToPaintMs,p99IntervalMs,maxIntervalMs,over16msPercent");
        for (int round = 0; round < 3; round++) {
            for (boolean direct : new boolean[] {round % 2 == 0, round % 2 != 0}) {
                System.setProperty("game.swingPresentation", Boolean.toString(!direct));
                measure(round, direct, 1, true);
            }
        }
    }

    private static void measure(int round, boolean immediate, int airDivisor, boolean combat)
            throws Exception {
        System.setProperty("game.timerPresentation", Boolean.toString(!immediate));
        var navigation = new GameNavigation();
        navigation.fixedResolution(1920, 1080);
        navigation.capture();
        if (airDivisor > 0)
            navigation.pose(new Vec3(10.879049f, 2.7514815f, 22.884184f), 1.251, .079);
        var session = new GpuRenderSession[1];
        var hooks =
                new GameRenderer.RuntimeHooks(
                        new GameDiagnostics(),
                        failure -> {},
                        (world, view, settings, backend) -> {
                            session[0] = new GpuRenderSession(world, view, settings);
                            return session[0];
                        });
        var previous = RepaintManager.currentManager(null);
        var panel = new GamePanel[1];
        var collector = new PaintCollector(panel);
        collector.direct = immediate;
        var window = new java.awt.Frame();
        try (var renderer =
                new GameRenderer(
                        navigation,
                        airDivisor > 0
                                ? AdventureWorld.create()
                                : BlockWorld.landscape().snapshot(),
                        "gpu",
                        GameRenderer::copyImage,
                        hooks)) {
            if (combat) renderer.enableShooter();
            if (airDivisor > 0) {
                renderer.atmosphereEnabled(true);
                renderer.configure(settings -> settings.withFogDownsample(airDivisor));
            }
            collector.renderer = renderer;
            SwingUtilities.invokeAndWait(
                    () -> {
                        RepaintManager.setCurrentManager(collector);
                        panel[0] = new GamePanel(navigation, renderer);
                        window.setUndecorated(true);
                        window.add(panel[0]);
                        window.setSize(3840, 2160);
                        window.addNotify();
                        window.validate();
                        if (window.isVisible())
                            throw new AssertionError("Benchmark must stay hidden");
                        panel[0].doLayout();
                    });
            long warm = System.nanoTime();
            while (System.nanoTime() - warm < 2_000_000_000L || renderer.frame() == null) {
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                if (System.nanoTime() - warm > 30_000_000_000L)
                    throw new AssertionError("Warmup timeout");
                Thread.sleep(5);
            }
            SwingUtilities.invokeAndWait(() -> collector.start = System.nanoTime());
            long start = collector.start;
            while (System.nanoTime() - start < 3_000_000_000L) {
                double time = (System.nanoTime() - start) / 1e9;
                if (airDivisor > 0)
                    navigation.pose(
                            new Vec3(
                                    10.879049f + (float) Math.sin(time) * .5f,
                                    2.7514815f,
                                    22.884184f + (float) time * .2f),
                            1.251 + Math.sin(time * .7) * .1,
                            .079);
                else
                    navigation.pose(
                            new Vec3((float) Math.sin(time) * 2, 2.4f, (float) (-6 + time)),
                            Math.sin(time * .7) * .15,
                            -.14);
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                Thread.sleep(5);
            }
            SwingUtilities.invokeAndWait(
                    () -> {
                        collector.stopped = true;
                        panel[0].close();
                        RepaintManager.setCurrentManager(previous);
                    });
            if (collector.intervals.isEmpty()) throw new AssertionError("No fresh paints");
            System.out.printf(
                    Locale.ROOT,
                    "%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    round,
                    immediate ? "native-hidden" : "swing-offscreen",
                    collector.count / 3.,
                    quantile(collector.intervals, .5),
                    quantile(collector.intervals, .95),
                    quantile(collector.latencies, .95),
                    quantile(collector.intervals, .99),
                    quantile(collector.intervals, 1),
                    100.0
                            * collector.intervals.stream().filter(value -> value > 16.67).count()
                            / collector.intervals.size());
        } finally {
            SwingUtilities.invokeAndWait(
                    () -> {
                        collector.stopped = true;
                        if (panel[0] != null) panel[0].close();
                        window.dispose();
                        RepaintManager.setCurrentManager(previous);
                    });
            if (session[0] != null && !session[0].awaitClosed(30))
                throw new AssertionError("Owner retirement");
        }
    }

    private static double quantile(ArrayList<Double> values, double fraction) {
        Collections.sort(values);
        return values.get(
                Math.min(values.size() - 1, (int) Math.ceil(values.size() * fraction) - 1));
    }

    private static final class PaintCollector extends RepaintManager {
        final GamePanel[] panel;
        final BufferedImage surface = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        final AtomicBoolean queued = new AtomicBoolean();
        final ArrayList<Double> intervals = new ArrayList<>();
        final ArrayList<Double> latencies = new ArrayList<>();
        GameRenderer renderer;
        boolean direct;
        volatile boolean stopped;
        long start;
        long last;
        long publication;
        int count;

        PaintCollector(GamePanel[] panel) {
            this.panel = panel;
        }

        @Override
        public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            if (component != panel[0]) {
                super.addDirtyRegion(component, x, y, width, height);
                return;
            }
            if (!stopped && queued.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::paint);
            }
        }

        private void paint() {
            queued.set(false);
            if (stopped) return;
            if (direct) {
                var frame = renderer.acquireFrame();
                if (frame == null) return;
                try {
                    if (frame.devicePixels() == null) return;
                    var field = GamePanel.class.getDeclaredField("nativeDisplay");
                    field.setAccessible(true);
                    var display = (GameDisplay) field.get(panel[0]);
                    var paint =
                            GamePanel.class.getDeclaredMethod(
                                    "drawFrameOverlay",
                                    java.awt.Graphics2D.class,
                                    GameRenderer.Frame.class);
                    paint.setAccessible(true);
                    int[] overlay =
                            GameOverlay.capture(
                                    3840,
                                    2160,
                                    1,
                                    false,
                                    draw -> {
                                        try {
                                            paint.invoke(panel[0], draw, frame);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                    display.submit(
                            frame,
                            3840,
                            2160,
                            overlay,
                            shown -> {
                                renderer.displayed(shown);
                                record();
                            });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    frame.release();
                }
            } else {
                var draw = surface.createGraphics();
                try {
                    panel[0].paint(draw);
                } finally {
                    draw.dispose();
                }
                record();
            }
        }

        private synchronized void record() {
            if (stopped) return;
            long now = System.nanoTime();
            var shown = renderer.displayedFrame();
            if (start == 0
                    || now - start >= 3_000_000_000L
                    || shown == null
                    || shown.publicationNanos() == publication) return;
            publication = shown.publicationNanos();
            if (last != 0) intervals.add((now - last) / 1e6);
            last = now;
            count++;
            latencies.add((now - publication) / 1e6);
        }
    }
}
