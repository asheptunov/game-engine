package game;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

public final class GameStallProbe {
    public static void main(String[] args) throws Exception {
        int width = args.length > 0 ? Integer.parseInt(args[0]) : 960;
        int height = width * 5 / 8;
        var nav = new GameNavigation();
        nav.fixedResolution(width, height);
        var route = CameraRoute.load(Path.of("benchmarks/game/camera-route.csv"));
        var panel = new GamePanel[1];
        var pending = new AtomicBoolean();
        var paints = new ArrayList<Double>();
        var delays = new ArrayList<Double>();
        var intervals = new ArrayList<Double>();
        var samples = new StringBuilder("seconds,queueMs,paintMs,intervalMs,shownGeneration,spp\n");
        long[] last = {0};
        long[] painted = {0};
        long[] copies = {0};
        var gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        long gcStart = gc.stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadContentionMonitoringEnabled(true);
        long[] edt = {0};
        try (var renderer = new GameRenderer(nav, BlockWorld.landscape(), "gpu")) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        panel[0] = new GamePanel(nav, renderer);
                        panel[0].setSize(960, 600);
                        panel[0].doLayout();
                        nav.capture();
                        edt[0] = Thread.currentThread().threadId();
                    });
            try {
                var image = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
                Thread.sleep(2000);
                renderer.observePublications(f -> copies[0]++);
                long start = System.nanoTime();
                var scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(
                        () -> {
                            if (!pending.compareAndSet(false, true)) return;
                            long queued = System.nanoTime();
                            SwingUtilities.invokeLater(
                                    () -> {
                                        try {
                                            long begin = System.nanoTime();
                                            route.apply(nav, (begin - start) / 1e9);
                                            var g = image.createGraphics();
                                            try {
                                                panel[0].paint(g);
                                            } finally {
                                                g.dispose();
                                            }
                                            long end = System.nanoTime();
                                            double duration = (end - begin) / 1e6,
                                                    delay = (begin - queued) / 1e6,
                                                    interval =
                                                            last[0] == 0
                                                                    ? 0
                                                                    : (end - last[0]) / 1e6;
                                            paints.add(duration);
                                            delays.add(delay);
                                            if (last[0] != 0) intervals.add(interval);
                                            last[0] = end;
                                            var f = renderer.frame();
                                            if (f != null && f.publicationNanos() != painted[0])
                                                painted[0] = f.publicationNanos();
                                            samples.append(
                                                    String.format(
                                                            Locale.ROOT,
                                                            "%.6f,%.3f,%.3f,%.3f,%d,%d%n",
                                                            (end - start) / 1e9,
                                                            delay,
                                                            duration,
                                                            interval,
                                                            f == null ? 0 : f.generation(),
                                                            f == null ? 0 : f.samples()));
                                        } finally {
                                            pending.set(false);
                                        }
                                    });
                        },
                        0,
                        16,
                        TimeUnit.MILLISECONDS);
                Thread.sleep(15000);
                scheduler.shutdownNow();
                SwingUtilities.invokeAndWait(() -> {});
                var info = bean.getThreadInfo(edt[0]);
                renderer.observePublications(null);
                long gcMs =
                        gc.stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum()
                                - gcStart;
                System.out.printf(
                        Locale.ROOT,
                        "grid=%dx%d paints=%d copies=%d paint p95=%.2f max=%.2f queue p95=%.2f"
                            + " max=%.2f interval p95=%.2f max=%.2f EDT blockedMs=%d GCms=%d%n",
                        width,
                        height,
                        paints.size(),
                        copies[0],
                        q(paints, .95),
                        q(paints, 1),
                        q(delays, .95),
                        q(delays, 1),
                        q(intervals, .95),
                        q(intervals, 1),
                        info.getBlockedTime(),
                        gcMs);
                Files.writeString(
                        Path.of(args.length > 1 ? args[1] : "out/stalls/probe.csv"), samples);
            } finally {
                SwingUtilities.invokeAndWait(panel[0]::close);
            }
        }
    }

    static double q(List<Double> values, double q) {
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
    }
}
