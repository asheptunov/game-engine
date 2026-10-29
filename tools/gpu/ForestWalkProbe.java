package game;

import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.input.Modifiers;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** Window-free reproduction of walking from the default forest spawn, including HUD paints. */
public final class ForestWalkProbe {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 35;
        String label = args.length > 1 ? args[1] : "before";
        var nav = new GameNavigation();
        var frames = new AtomicInteger();
        var paints = new ArrayList<Double>();
        var gaps = new ArrayList<Double>();
        var first = new java.util.concurrent.atomic.AtomicLong();
        long launched = System.nanoTime();
        long[] previous = {0};
        var bean = ManagementFactory.getThreadMXBean();
        bean.setThreadContentionMonitoringEnabled(true);
        try (var renderer = new GameRenderer(nav, AdventureWorld.create(), "gpu")) {
            renderer.dayCycle(true);
            nav.speed(5);
            renderer.enableShooter();
            if (args.length > 2) renderer.configure(s -> s.withBloom(engine.Bloom.valueOf(args[2])));
            nav.walking(true);
            var panel = new GamePanel[1];
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new GamePanel(nav, renderer);
                panel[0].setSize(960, 600);
                panel[0].doLayout();
            });
            renderer.observePublications(frame -> {
                long now = System.nanoTime();
                first.compareAndSet(0, now);
                synchronized (gaps) {
                    if (previous[0] != 0) { gaps.add((now - previous[0]) / 1e6); }
                }
                previous[0] = now;
                frames.incrementAndGet();
            });
            var image = new java.awt.image.BufferedImage(960, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
            nav.capture();
            nav.key(KeyInput.press(KeyCode.W, Modifiers.NONE));
            long start = System.nanoTime();
            long lastReport = start;
            int segment = 0;
            try {
                while (System.nanoTime() - start < seconds * 1e9) {
                    int nextSegment = (int) ((System.nanoTime() - start) / 2_000_000_000L);
                    if (nextSegment != segment) {
                        nav.key(new KeyInput(segment % 2 == 0 ? KeyCode.W : KeyCode.S, false, Modifiers.NONE));
                        segment = nextSegment;
                        nav.key(KeyInput.press(segment % 2 == 0 ? KeyCode.W : KeyCode.S, Modifiers.NONE));
                    }
                    long begin = System.nanoTime();
                    SwingUtilities.invokeAndWait(() -> {
                        var draw = image.createGraphics();
                        try { panel[0].paint(draw); } finally { draw.dispose(); }
                    });
                    paints.add((System.nanoTime() - begin) / 1e6);
                    if (System.nanoTime() - lastReport > 1e9) {
                        lastReport = System.nanoTime();
                        System.out.printf("elapsed=%.1f frames=%d paintMs=%.2f heapMiB=%d eye=%s error=%s%n",
                                (lastReport - start) / 1e9, frames.get(), paints.getLast(),
                                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024), nav.snapshot().position(), renderer.error());
                    }
                    Thread.sleep(16);
                }
                nav.release();
                System.out.printf("RESULT %s frames=%d paintP95=%.2f paintMax=%.2f gapP95=%.2f gapMax=%.2f%n",
                        label, frames.get(), percentile(paints, .95), percentile(paints, 1), percentile(gaps, .95), percentile(gaps, 1));
                System.out.printf("FIRST IMAGE %.2f ms after launch; %s%n", (first.get() - launched) / 1e6, renderer.backendStatus());
                try {
                    var capture = renderer.captureBundle(true, Path.of("out/forest-profile/captures")).get(90, java.util.concurrent.TimeUnit.SECONDS);
                    System.out.println("CAPTURE " + capture);
                    System.out.println(CaptureReplay.replay(GameCapture.load(capture)));
                } catch (Exception error) { System.out.println("CAPTURE FAILED " + error); }
            } finally {
                SwingUtilities.invokeAndWait(panel[0]::close);
            }
        }
    }

    private static double percentile(ArrayList<Double> values, double fraction) {
        if (values.isEmpty()) { return 0; }
        ArrayList<Double> sorted;
        synchronized (values) { sorted = new ArrayList<>(values); }
        sorted.sort(Double::compare);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(fraction * sorted.size()) - 1));
    }
}
