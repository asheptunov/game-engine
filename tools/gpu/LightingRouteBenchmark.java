package game;

import engine.Bloom;

import gpu.GpuRenderSession;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

/**
 * Real combat simulation and 1080p delivery; offscreen Swing or hidden native, excluding scanout.
 */
public final class LightingRouteBenchmark {
    private static final long DURATION = 12_000_000_000L;

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 2 ? args[2] : "swing-offscreen";
        if (!Set.of("swing-offscreen", "native-hidden").contains(endpoint))
            throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
        System.setProperty(
                "game.nativePresentation", Boolean.toString(endpoint.equals("native-hidden")));
        System.setProperty("game.swingPresentation", "false");
        System.setProperty("game.timerPresentation", "false");
        System.setProperty("game.profileFrames", "true");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var modes =
                args.length > 1
                        ? args[1].split(",")
                        : new String[] {"raw1080", "temporal1080", "temporal720", "temporal540"};
        System.out.println(
                "round,mode,separateSceneSubmission,freshPaintsPerSecond,medianMs,p95Ms,p99Ms,p95AgeMs,medianTraceMs,medianCopyMs");
        boolean compare = args.length > 3 && args[3].equals("compare-submissions");
        boolean threeRounds = args.length > 3 && args[3].equals("three-rounds");
        for (int round = 0; round < (compare || threeRounds ? 3 : 2); round++) {
            for (int i = 0; i < modes.length; i++) {
                String mode = modes[round % 2 == 0 ? i : modes.length - 1 - i];
                if (compare) {
                    for (boolean separate : new boolean[] {round % 2 == 0, round % 2 != 0}) {
                        System.setProperty(
                                "game.separateGpuSceneSubmission", Boolean.toString(separate));
                        Path variant = output.resolve(separate ? "separate" : "batched");
                        Files.createDirectories(variant);
                        measure(variant, round, mode);
                    }
                } else measure(output, round, mode);
            }
        }
    }

    private static void measure(Path output, int round, String mode) throws Exception {
        var navigation = new GameNavigation();
        navigation.active(false);
        boolean fog = mode.startsWith("fog-");
        String skyMode = fog ? mode.substring(4) : mode;
        boolean sun = skyMode.startsWith("sun-");
        String transportMode = sun ? skyMode.substring(4) : skyMode;
        boolean roulette = transportMode.startsWith("roulette-");
        String baseMode = roulette ? transportMode.substring(9) : transportMode;
        int height = baseMode.endsWith("540") ? 540 : baseMode.endsWith("720") ? 720 : 1080;
        if (!Set.of(
                        "raw1080",
                        "temporal1080",
                        "temporal720",
                        "temporal540",
                        "split540",
                        "split720",
                        "nrd1080",
                        "nrd540",
                        "nrdsplit540", "fsr540", "fsr720")
                .contains(baseMode))
            throw new IllegalArgumentException("Unknown lighting benchmark mode: " + mode);
        boolean fsr = baseMode.startsWith("fsr");
        boolean split = baseMode.contains("split");
        navigation.fixedResolution(split || fsr ? 1920 : height * 16 / 9, split || fsr ? 1080 : height);
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
        collector.nativeEndpoint = Boolean.getBoolean("game.nativePresentation");
        var window = collector.nativeEndpoint ? new java.awt.Frame() : null;
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
            renderer.fogEnabled(fog);
            renderer.configure(
                    s ->
                            s.withDenoiser(
                                            (baseMode.startsWith("nrd") || fsr)
                                                    ? engine.Denoiser.NRD_RELAX
                                                    : engine.Denoiser.BUILT_IN)
                                    .withUpscaler(!fsr ? engine.Upscaler.OFF : height == 540
                                            ? engine.Upscaler.FSR_PERFORMANCE : engine.Upscaler.FSR_QUALITY)
                                    .withPathRoulette(roulette)
                                    .withPathDepth(8)
                                    .withSamplesPerBatch(1)
                                    .withSampleTarget(0)
                                    .withInteractive(false, 1000. / 120, 0, 0)
                                    .withFoveated(false)
                                    .withFogDownsample(1)
                                    .withTemporal(!baseMode.startsWith("raw"), 1)
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
                        if (window != null) {
                            window.setUndecorated(true);
                            window.add(panel[0]);
                            window.setSize(1920, 1080);
                            window.addNotify();
                            window.validate();
                            if (window.isVisible())
                                throw new AssertionError("Window must stay hidden");
                        }
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
            renderer.timeOfDay(9);
            renderer.dayDuration(
                    Double.parseDouble(System.getProperty("game.benchmarkDaySeconds", "600")));
            renderer.dayCycle(sun);
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
            collector.writeProfile(output.resolve(mode + "-" + round + "-profile.csv"), session[0]);
            Files.writeString(
                    output.resolve(mode + "-" + round + "-settings.txt"),
                    renderer.settings()
                            + "\n"
                            + renderer.backendStatus()
                            + "\n"
                            + renderer.shooter().status()
                            + "\nendpoint="
                            + (collector.nativeEndpoint ? "native-hidden" : "swing-offscreen")
                            + "\nseparateGpuSceneSubmission="
                            + Boolean.getBoolean("game.separateGpuSceneSubmission")
                            + "\n");
            if (!collector.nativeEndpoint)
                javax.imageio.ImageIO.write(
                        collector.surface,
                        "png",
                        output.resolve(mode + "-" + round + ".png").toFile());
            System.out.printf(
                    Locale.ROOT,
                    "%d,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    round,
                    mode,
                    Boolean.getBoolean("game.separateGpuSceneSubmission"),
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
                        if (window != null) window.dispose();
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
                        "seconds,intervalMs,ageMs,traceMs,copyMs,width,height,health,rounds,solarX,solarY,solarZ\n");
        GameRenderer renderer;
        boolean nativeEndpoint;
        volatile boolean stopped;
        volatile long start;
        volatile long queuedAt;
        long last, publication;
        int count;
        final List<PaintTiming> paints = new ArrayList<>();

        private record PaintTiming(
                long capturedNanos,
                long samples,
                long publicationNanos,
                GameRenderer.Metrics metrics,
                long ended,
                long queueNanos,
                long paintNanos) {}

        Collector(GamePanel[] panel) {
            this.panel = panel;
        }

        public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            if (component != panel[0]) {
                super.addDirtyRegion(component, x, y, width, height);
                return;
            }
            if (!stopped && queued.compareAndSet(false, true)) {
                queuedAt = System.nanoTime();
                SwingUtilities.invokeLater(this::paint);
            }
        }

        private void paint() {
            long paintStart = System.nanoTime();
            long queueNanos = paintStart - queuedAt;
            queued.set(false);
            if (stopped || panel[0] == null) return;
            if (nativeEndpoint) {
                presentNative(paintStart, queueNanos);
                return;
            }
            var draw = surface.createGraphics();
            try {
                panel[0].paint(draw);
            } finally {
                draw.dispose();
            }
            record(renderer.displayedFrame(), System.nanoTime(), paintStart, queueNanos);
        }

        private void presentNative(long paintStart, long queueNanos) {
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
                boolean full =
                        renderer.shooter().status().hurtTime() > 0
                                || !renderer.shooter().tracers().isEmpty();
                int[] overlay =
                        GameOverlay.capture(
                                1920,
                                1080,
                                1,
                                full,
                                draw -> {
                                    try {
                                        paint.invoke(panel[0], draw, frame);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                display.submit(
                        frame,
                        1920,
                        1080,
                        overlay,
                        shown -> {
                            renderer.displayed(shown);
                            long ended = System.nanoTime();
                            // All collector state stays on the EDT; preserve the actual submission
                            // time.
                            shown.retain();
                            SwingUtilities.invokeLater(
                                    () -> {
                                        try {
                                            record(shown, ended, paintStart, queueNanos);
                                        } finally {
                                            shown.release();
                                        }
                                    });
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                frame.release();
            }
        }

        private void record(GameRenderer.Frame frame, long now, long paintStart, long queueNanos) {
            if (stopped
                    || start == 0
                    || now - start >= DURATION
                    || frame == null
                    || frame.publicationNanos() == publication) return;
            paints.add(
                    new PaintTiming(
                            frame.capturedNanos(),
                            frame.samples(),
                            frame.publicationNanos(),
                            frame.metrics(),
                            now,
                            queueNanos,
                            now - paintStart));
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
            var solar = frame.context().world().sky().atmosphere().solarDirection();
            rows.append(
                    String.format(
                            Locale.ROOT,
                            "%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.9f,%.9f,%.9f%n",
                            (now - start) / 1e9,
                            interval,
                            age,
                            frame.metrics().traceNanos() / 1e6,
                            frame.metrics().copyNanos() / 1e6,
                            frame.width(),
                            frame.height(),
                            status.health(),
                            status.rounds(),
                            solar.x(),
                            solar.y(),
                            solar.z()));
        }

        private void writeProfile(Path output, GpuRenderSession session) throws Exception {
            var timings = new HashMap<String, GpuRenderSession.FrameTiming>();
            for (var timing : session.frameTimings()) {
                timings.put(timing.capturedNanos() + ":" + timing.samples(), timing);
            }
            try (var writer = Files.newBufferedWriter(output)) {
                writer.write(
                        "seconds,queueMs,paintMs,pickupMs,copyMs,copyToPaintMs,hostMs,prepareMs,gpuMs,submitWaitMs,readbackMs\n");
                for (var paint : paints) {
                    var timing = timings.get(paint.capturedNanos() + ":" + paint.samples());
                    if (timing == null || timing.hostNanos() != paint.metrics().traceNanos()) {
                        throw new AssertionError(
                                "Missing or inconsistent GPU timing for painted frame");
                    }
                    writer.write(
                            String.format(
                                    Locale.ROOT,
                                    "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                                    (paint.ended() - start) / 1e9,
                                    paint.queueNanos() / 1e6,
                                    paint.paintNanos() / 1e6,
                                    paint.metrics().pickupNanos() / 1e6,
                                    paint.metrics().copyNanos() / 1e6,
                                    (paint.ended() - paint.publicationNanos()) / 1e6,
                                    timing.hostNanos() / 1e6,
                                    timing.prepareNanos() / 1e6,
                                    timing.executeNanos() / 1e6,
                                    timing.submitWaitNanos() / 1e6,
                                    timing.readbackNanos() / 1e6));
                }
            }
        }
    }
}
