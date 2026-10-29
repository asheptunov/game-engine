package game;

import engine.Bloom;

import gpu.GpuRenderSession;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

/** Real combat simulation and 1080p Swing painting, offscreen; excludes scanout and real input. */
public final class LightingRouteBenchmark {
    private static final long DURATION = 12_000_000_000L;

    public static void main(String[] args) throws Exception {
        System.setProperty("game.nativePresentation", "false");
        System.setProperty("game.profileFrames", "true");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var modes =
                args.length > 1
                        ? args[1].split(",")
                        : new String[] {"raw1080", "temporal1080", "temporal720", "temporal540"};
        System.out.println(
                "round,mode,freshPaintsPerSecond,medianMs,p95Ms,p99Ms,p95AgeMs,medianTraceMs,medianCopyMs");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < modes.length; i++)
                measure(output, round, modes[round == 0 ? i : modes.length - 1 - i]);
        }
    }

    private static void measure(Path output, int round, String mode) throws Exception {
        var navigation = new GameNavigation();
        navigation.active(false);
        int height = mode.endsWith("540") ? 540 : mode.endsWith("720") ? 720 : 1080;
        if (!Set.of(
                        "raw1080",
                        "temporal1080",
                        "temporal720",
                        "temporal540",
                        "split540",
                        "split720",
                        "nrd1080",
                        "nrd540",
                        "nrdsplit540")
                .contains(mode))
            throw new IllegalArgumentException("Unknown lighting benchmark mode: " + mode);
        boolean split = mode.contains("split");
        navigation.fixedResolution(split ? 1920 : height * 16 / 9, split ? 1080 : height);
        navigation.pose(new Vec3(0, 2.4f, -6), 0, -.08);
        navigation.optics(0, 12);
        var session = new GpuRenderSession[1];
        var hooks =
                new GameRenderer.RuntimeHooks(
                        new GameDiagnostics(),
                        failure -> {},
                        (world, view, settings, backend) ->
                                session[0] = new GpuRenderSession(world, view, settings));
        var panel = new GamePanel[1];
        var collector = new Collector(panel);
        var previous = RepaintManager.currentManager(null);
        try (var renderer =
                new GameRenderer(
                        navigation,
                        AdventureWorld.create(),
                        "gpu",
                        GameRenderer::copyImage,
                        hooks)) {
            renderer.enableShooter();
            renderer.atmosphereEnabled(true);
            renderer.configure(
                    s ->
                            s.withDenoiser(
                                            mode.startsWith("nrd")
                                                    ? engine.Denoiser.NRD_RELAX
                                                    : engine.Denoiser.BUILT_IN)
                                    .withPathDepth(8)
                                    .withSamplesPerBatch(1)
                                    .withSampleTarget(0)
                                    .withInteractive(false, 1000. / 120, 0, 0)
                                    .withFoveated(false)
                                    .withFogDownsample(1)
                                    .withTemporal(!mode.startsWith("raw"), 1)
                                    .withBloom(Bloom.OFF));
            if (split) {
                renderer.configure(s -> s.withLightingScale(height / 1080.0));
            }
            collector.renderer = renderer;
            SwingUtilities.invokeAndWait(
                    () -> {
                        RepaintManager.setCurrentManager(collector);
                        panel[0] = new GamePanel(navigation, renderer);
                        panel[0].setSize(1920, 1080);
                        panel[0].doLayout();
                    });
            navigation.active(true);
            navigation.capture();
            long warm = System.nanoTime();
            while (renderer.frame() == null || System.nanoTime() - warm < 2_000_000_000L) {
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                if (System.nanoTime() - warm > 60_000_000_000L)
                    throw new AssertionError("Warmup timeout");
                Thread.sleep(5);
            }
            renderer.shooter().reset();
            SwingUtilities.invokeAndWait(
                    () -> {
                        collector.start = System.nanoTime();
                        collector.last = collector.start;
                    });
            long start = collector.start;
            int lastShot = -1;
            while (System.nanoTime() - start < DURATION) {
                double t = (System.nanoTime() - start) / 1e9;
                // Fixed elapsed-time route: strafe/walk, fast turns, aim, fire and reveal surfaces.
                double turn =
                        t < 4
                                ? .12 * Math.sin(t * 1.4)
                                : t < 8 ? .45 * Math.sin((t - 4) * 2) : .08 * Math.sin(t);
                navigation.pose(
                        new Vec3((float) (Math.sin(t * .8) * 1.5), 2.4f, (float) (-6 + t * .35)),
                        turn,
                        -.06);
                navigation.scoped(t >= 8 && t < 10);
                int shot = (int) (t / 2);
                if (shot != lastShot) {
                    lastShot = shot;
                    renderer.shooter().fire(navigation.view().camera(), navigation.aimAmount());
                }
                if (renderer.error() != null) throw new AssertionError(renderer.error());
                Thread.sleep(2);
            }
            SwingUtilities.invokeAndWait(
                    () -> {
                        collector.stopped = true;
                        panel[0].close();
                    });
            collector.intervals.add((start + DURATION - collector.last) / 1e6);
            Files.writeString(output.resolve(mode + "-" + round + ".csv"), collector.rows);
            Files.writeString(
                    output.resolve(mode + "-" + round + "-settings.txt"),
                    renderer.settings()
                            + "\n"
                            + renderer.backendStatus()
                            + "\n"
                            + renderer.shooter().status()
                            + "\n");
            javax.imageio.ImageIO.write(
                    collector.surface, "png", output.resolve(mode + "-" + round + ".png").toFile());
            System.out.printf(
                    Locale.ROOT,
                    "%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    round,
                    mode,
                    collector.count / (DURATION / 1e9),
                    q(collector.intervals, .5),
                    q(collector.intervals, .95),
                    q(collector.intervals, .99),
                    q(collector.ages, .95),
                    q(collector.traces, .5),
                    q(collector.copies, .5));
        } finally {
            SwingUtilities.invokeAndWait(
                    () -> {
                        collector.stopped = true;
                        if (panel[0] != null) panel[0].close();
                        RepaintManager.setCurrentManager(previous);
                    });
            if (session[0] != null && !session[0].awaitClosed(30))
                throw new AssertionError("GPU retirement");
        }
    }

    private static double q(List<Double> values, double fraction) {
        if (values.isEmpty()) throw new AssertionError("No fresh frames");
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(
                Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    private static final class Collector extends RepaintManager {
        final GamePanel[] panel;
        final BufferedImage surface = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        final AtomicBoolean queued = new AtomicBoolean();
        final List<Double> intervals = new ArrayList<>(),
                ages = new ArrayList<>(),
                traces = new ArrayList<>(),
                copies = new ArrayList<>();
        final StringBuilder rows =
                new StringBuilder(
                        "seconds,intervalMs,ageMs,traceMs,copyMs,width,height,health,rounds\n");
        GameRenderer renderer;
        volatile boolean stopped;
        volatile long start;
        long last, publication;
        int count;

        Collector(GamePanel[] panel) {
            this.panel = panel;
        }

        public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            if (component != panel[0]) {
                super.addDirtyRegion(component, x, y, width, height);
                return;
            }
            if (!stopped && queued.compareAndSet(false, true))
                SwingUtilities.invokeLater(this::paint);
        }

        private void paint() {
            queued.set(false);
            if (stopped || panel[0] == null) return;
            var draw = surface.createGraphics();
            try {
                panel[0].paint(draw);
            } finally {
                draw.dispose();
            }
            long now = System.nanoTime();
            var frame = renderer.displayedFrame();
            if (start == 0
                    || now - start >= DURATION
                    || frame == null
                    || frame.publicationNanos() == publication) return;
            publication = frame.publicationNanos();
            count++;
            double interval = (now - last) / 1e6;
            last = now;
            double age = (frame.metrics().ageNanos() + now - frame.publicationNanos()) / 1e6;
            intervals.add(interval);
            ages.add(age);
            traces.add(frame.metrics().traceNanos() / 1e6);
            copies.add(frame.metrics().copyNanos() / 1e6);
            var status = renderer.shooter().status();
            rows.append(
                    String.format(
                            Locale.ROOT,
                            "%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d%n",
                            (now - start) / 1e9,
                            interval,
                            age,
                            frame.metrics().traceNanos() / 1e6,
                            frame.metrics().copyNanos() / 1e6,
                            frame.image().getWidth(),
                            frame.image().getHeight(),
                            status.health(),
                            status.rounds()));
        }
    }
}
