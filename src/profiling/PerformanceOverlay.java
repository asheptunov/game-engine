package profiling;

import di.annotations.Inject;
import di.annotations.Named;
import rendering.*;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Locale;

/** Independent pipeline renderer. Cached panel refreshes at 4 Hz; collection stays frame-granular. */
public final class PerformanceOverlay implements Renderer {
    private static final int[] COLORS = {
            0x8794a8, 0xaa82ef, 0x49c9ef, 0xf3c65a, 0x6bdd99,
            0xf78bc4, 0xf09a63, 0xb5bec9, 0x465268
    };
    private static final String[] LABELS = {
            "background", "scene", "trace", "resample", "paint", "overlay", "present", "other", "idle"
    };
    private final FrameProfiler profiler;
    private final Painter painter;
    private final BufferedImage panel;
    private final rendering.Color[] cachedPixels;
    private final int[] pixels;
    private final double budgetMs;
    private long lastRefresh;
    private boolean wasVisible;

    @Inject
    public PerformanceOverlay(FrameProfiler profiler, Raster raster, @Named("frame_rate") int rate) {
        this.profiler = profiler;
        painter = new RasterPainter(raster);
        panel = new BufferedImage(Math.min(760, raster.width() - 24), 340, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) panel.getRaster().getDataBuffer()).getData();
        cachedPixels = new rendering.Color[pixels.length];
        budgetMs = 1000. / rate;
    }

    @Override public void render() {
        if (!profiler.visible()) { wasVisible = false; return; }
        long now = profiler.now();
        if (!wasVisible || now - lastRefresh >= 250_000_000L) {
            refresh(profiler.snapshot(), now);
            var colors = new java.util.HashMap<Integer, rendering.Color>();
            for (int i = 0; i < pixels.length; i++) {
                cachedPixels[i] = colors.computeIfAbsent(pixels[i], rendering.Color.RgbInt24Color::of);
            }
            lastRefresh = now;
        }
        wasVisible = true;
        painter.drawImg(12, 12, panel.getWidth(), panel.getHeight(),
                (i, x, y) -> cachedPixels[y * panel.getWidth() + x], BlendMode.OVER_PRE);
    }

    private void refresh(FrameProfiler.Snapshot snapshot, long now) {
        Graphics2D g = panel.createGraphics();
        try {
            g.setColor(new java.awt.Color(0x141c29));
            g.fillRect(0, 0, panel.getWidth(), panel.getHeight());
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            text(g, "PERFORMANCE  [F3]    rolling 10s / exclusive wall time", 14, 22);
            List<FrameProfiler.Frame> frames = snapshot.frames();
            if (frames.isEmpty()) { text(g, "Waiting for completed frames...", 14, 45); return; }
            text(g, format("%.1f FPS    avg %.2f ms    p95 %.2f ms    budget %.2f ms",
                    snapshot.fps(), snapshot.meanMs(), snapshot.p95Ms(), budgetMs), 14, 43);
            var latest = frames.getLast();
            var rays = latest.rays();
            if (rays == null) {
                text(g, "Texture editor    ray statistics: n/a", 14, 64);
            } else {
                text(g, format("Render %dx%d    %,d rays    %.2f Mrays/s    trace %.2f ms",
                        rays.width(), rays.height(), rays.primary(),
                        rays.traceNanos() > 0 ? rays.primary() * 1000. / rays.traceNanos() : 0,
                        rays.traceNanos() / 1e6), 14, 64);
                text(g, format("Hits %,d    shadows %,d / %,d blocked    lit pixels %,d",
                        rays.hits(), rays.occluded(), rays.shadows(), rays.lit()), 14, 82);
            }
            double[] means = new double[COLORS.length];
            for (var f : frames) for (int i = 0; i < means.length; i++) means[i] += f.nanos().get(i) / 1e6 / frames.size();
            for (int i = 0; i < means.length; i++) {
                int x = 14 + (i % 3) * ((panel.getWidth() - 28) / 3);
                int y = 104 + (i / 3) * 18;
                g.setColor(new java.awt.Color(COLORS[i]));
                g.fillRect(x, y - 9, 8, 8);
                text(g, format("%s %.2fms %2.0f%%", LABELS[i], means[i],
                        snapshot.meanMs() > 0 ? 100 * means[i] / snapshot.meanMs() : 0), x + 12, y);
            }
            int left = 54, top = 167, bottom = 299, width = panel.getWidth() - left - 16;
            double maxMs = Math.max(budgetMs * 2, frames.stream().mapToDouble(f -> f.duration() / 1e6).max().orElse(0) * 1.1);
            g.setColor(new java.awt.Color(0x263247));
            g.fillRect(left, top, width, bottom - top);
            // One representative (slowest) frame per time column preserves spikes at high FPS.
            FrameProfiler.Frame[] columns = new FrameProfiler.Frame[width];
            for (var f : frames) {
                int x = (int) ((f.end() - (now - 10_000_000_000L)) / 10_000_000_000. * (width - 1));
                if (x >= 0 && x < width && (columns[x] == null || columns[x].duration() < f.duration())) columns[x] = f;
            }
            for (int x = 0; x < width; x++) {
                var f = columns[x];
                if (f == null) continue;
                double sum = 0;
                for (int i = 0; i < COLORS.length; i++) {
                    int y0 = bottom - (int) Math.round(sum / maxMs * (bottom - top));
                    sum += f.nanos().get(i) / 1e6;
                    int y1 = bottom - (int) Math.round(sum / maxMs * (bottom - top));
                    g.setColor(new java.awt.Color(COLORS[i]));
                    g.fillRect(left + x, y1, 1, y0 - y1);
                }
            }
            int budgetY = bottom - (int) Math.round(budgetMs / maxMs * (bottom - top));
            g.setColor(java.awt.Color.WHITE);
            g.drawLine(left, budgetY, left + width - 1, budgetY);
            text(g, format("%.1f", maxMs), 8, top + 5);
            text(g, "ms", 14, top + 21);
            text(g, "0", 28, bottom);
            text(g, "-10s", left, 318);
            text(g, "now", left + width - 24, 318);
            text(g, "Tallest frame per column; white line = target budget", 14, 335);
        } finally { g.dispose(); }
    }
    private static String format(String pattern, Object... args) { return String.format(Locale.ROOT, pattern, args); }
    private static void text(Graphics2D g, String value, int x, int y) {
        g.setColor(new java.awt.Color(0xe5edf8));
        g.drawString(value, x, y);
    }
}
