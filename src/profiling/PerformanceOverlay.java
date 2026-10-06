package profiling;

import di.annotations.Inject;
import di.annotations.Named;
import rendering.*;

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
    private final Raster raster;
    private final BufferedImage panel;
    private final byte[][] cachedChannels;
    private final int[] pixels;
    private final double budgetMs;
    private long lastRefresh;
    private boolean wasVisible;
    private boolean wereDetails;
    private final RuntimeMetrics metrics = new RuntimeMetrics();

    @Inject
    public PerformanceOverlay(FrameProfiler profiler, Raster raster, @Named("frame_rate") int rate) {
        this.profiler = profiler;
        this.raster = raster;
        panel = new BufferedImage(Math.max(1, Math.min(760, raster.width() - 24)), 720, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) panel.getRaster().getDataBuffer()).getData();
        cachedChannels = new byte[3][pixels.length];
        budgetMs = 1000. / rate;
    }

    @Override public void render() {
        if (!profiler.visible()) { wasVisible = false; return; }
        long now = profiler.now();
        if (!wasVisible || wereDetails != profiler.traceDetails() || now - lastRefresh >= 250_000_000L) {
            refresh(profiler.snapshot(), now);
            for (int i = 0; i < pixels.length; i++) {
                cachedChannels[0][i] = (byte) (pixels[i] >> 16);
                cachedChannels[1][i] = (byte) (pixels[i] >> 8);
                cachedChannels[2][i] = (byte) pixels[i];
            }
            lastRefresh = now;
        }
        wasVisible = true;
        wereDetails = profiler.traceDetails();
        // The cached panel is opaque; copying channels avoids making the profiler a blending bottleneck.
        int height = Math.min(wereDetails ? 720 : 524, raster.height() - 12);
        int width = Math.min(panel.getWidth(), raster.width() - 12);
        if (width <= 0) return;
        for (int y = 0; y < height; y++) {
            int dest = (y + 12) * raster.width() + 12, source = y * panel.getWidth();
            java.util.Arrays.fill(raster.alpha(), dest, dest + width, (byte) 255);
            System.arraycopy(cachedChannels[0], source, raster.red(), dest, width);
            System.arraycopy(cachedChannels[1], source, raster.green(), dest, width);
            System.arraycopy(cachedChannels[2], source, raster.blue(), dest, width);
        }
    }

    private void refresh(FrameProfiler.Snapshot snapshot, long now) {
        Graphics2D g = panel.createGraphics();
        try {
            g.setColor(new java.awt.Color(0x141c29));
            g.fillRect(0, 0, panel.getWidth(), panel.getHeight());
            g.setFont(AwtText.monospaced(12));
            boolean async=profiler.renderProgress()!=null;
            var images=profiler.imageSnapshot();
            text(g, "PERFORMANCE [F3]  Trace detail [F4]  fresh 2s / display 10s", 14, 22);
            List<FrameProfiler.Frame> frames = snapshot.frames();
            if (frames.isEmpty()) { text(g, "Waiting for completed frames...", 14, 45); return; }
            if(async) {
                text(g,format("Fresh images: %s FPS   %s",images.fps()<0?"warming up":format("%.1f",images.fps()),images.status()),14,43);
                text(g,format("Update interval avg %.1fms / p95 %.1fms   current hold %.1fms",
                        images.meanMs(),images.p95Ms(),images.holdMs()),14,61);
                text(g,format("Camera image age now %s ms / at-update p95 %.1fms (through AWT submission)",
                        images.ageMs()<0?"n/a":format("%.1f",images.ageMs()),images.ageP95Ms()),14,79);
            } else text(g,"Display timing; fresh-image metrics apply to the live viewport",14,43);
            text(g, format("Display loop %.1f FPS   avg %.2fms / p95 %.2fms   target %.2fms",
                    snapshot.fps(),snapshot.meanMs(),snapshot.p95Ms(),budgetMs),14,103);
            var latest = frames.getLast();
            var rays = latest.rays();
            if (rays == null) {
                text(g, "Texture editor    ray statistics: n/a", 14, 124);
            } else {
                text(g, format("Last %strace %dx%d: %,d primary, %.2f Mprimary/s, %.2fms",
                        async?"background ":"",
                        rays.width(), rays.height(), rays.primary(),
                        rays.traceNanos() > 0 ? rays.primary() * 1000. / rays.traceNanos() : 0,
                        rays.traceNanos() / 1e6), 14, 124);
                text(g, async ? (profiler.viewportQuality()==null ? "Display-thread work per update (includes cached redraws)" : profiler.viewportQuality())
                        : format("Primary hits %,d    blocked %,d / %,d visibility    lit samples %,d",
                        rays.hits(), rays.occluded(), rays.shadows(), rays.lit()), 14, 142);
            }
            double[] means = new double[COLORS.length];
            for (var f : frames) for (int i = 0; i < means.length; i++) means[i] += f.nanos().get(i) / 1e6 / frames.size();
            for (int i = 0; i < means.length; i++) {
                int x = 14 + (i % 3) * ((panel.getWidth() - 28) / 3);
                int y = 164 + (i / 3) * 18;
                g.setColor(new java.awt.Color(COLORS[i]));
                g.fillRect(x, y - 9, 8, 8);
                text(g, async && i==FrameProfiler.Stage.TRACE.ordinal() ? "trace: background"
                        : format("%s %.2fms %2.0f%%", i==FrameProfiler.Stage.RESAMPLE.ordinal()?"filter/map":LABELS[i], means[i],
                        snapshot.meanMs() > 0 ? 100 * means[i] / snapshot.meanMs() : 0), x + 12, y);
            }
            if(async && profiler.viewportHistory()!=null) text(g,profiler.viewportHistory(),14,218);
            int left = 54, top = 227, bottom = 359, width = panel.getWidth() - left - 16;
            double target=async?1000./60:budgetMs;
            double maxMs = Math.max(target*2, (async
                    ? Math.max(images.holdMs(),images.updates().stream().mapToDouble(i->i.intervalNanos()/1e6).max().orElse(0))
                    : frames.stream().mapToDouble(f -> f.duration() / 1e6).max().orElse(0))*1.1);
            g.setColor(new java.awt.Color(0x263247));
            g.fillRect(left, top, width, bottom - top);
            // One representative (slowest) frame per time column preserves spikes at high FPS.
            FrameProfiler.Frame[] columns = new FrameProfiler.Frame[width];
            for (var f : frames) {
                int x = (int) ((f.end() - (now - 10_000_000_000L)) / 10_000_000_000. * (width - 1));
                if (x >= 0 && x < width && (columns[x] == null || columns[x].duration() < f.duration())) columns[x] = f;
            }
            for (int x = 0; !async && x < width; x++) {
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
            if(async) {
                double[] freshColumns=new double[width];
                for(var update:images.updates()) {
                    int x=(int)((update.time()-(now-10_000_000_000L))/10_000_000_000.*(width-1));
                    if(x<0 || x>=width) continue;
                    freshColumns[x]=Math.max(freshColumns[x],update.intervalNanos()/1e6);
                }
                for(int x=0;x<width;x++) {
                    int bar=(int)Math.round(freshColumns[x]/maxMs*(bottom-top));
                    g.setColor(new java.awt.Color(0x49c9ef)); g.fillRect(left+x,bottom-bar,1,bar);
                }
                int hold=(int)Math.round(images.holdMs()/maxMs*(bottom-top));
                g.setColor(new java.awt.Color(0xf09a63)); g.fillRect(left+width-3,bottom-hold,3,hold);
            }
            int budgetY = bottom - (int) Math.round(target / maxMs * (bottom - top));
            g.setColor(java.awt.Color.WHITE);
            g.drawLine(left, budgetY, left + width - 1, budgetY);
            text(g, format("%.1f", maxMs), 8, top + 5);
            text(g, "ms", 14, top + 21);
            text(g, "0", 28, bottom);
            text(g, "-10s", left, 378);
            text(g, "now", left + width - 24, 378);
            text(g,async ? "Fresh intervals; stages include cached redraws / orange hold / white 60 FPS" :
                    "Display-thread stages; tallest interval per column / white = target",14,395);
            hardware(g, latest, metrics.sample());
            if (profiler.traceDetails()) traceDetails(g, frames);
        } finally { g.dispose(); }
    }

    private void hardware(Graphics2D g, FrameProfiler.Frame latest, RuntimeMetrics.Snapshot hw) {
        var progress=profiler.renderProgress();
        if(progress != null) text(g,format("Gen %d/%d first raster %.0fms cancel %d waste %,d paths tile max %.1fms",
                progress.generation(),progress.shownGeneration(),
                progress.firstImageNanos()/1e6,progress.cancelledJobs(),progress.wastedPrimaryRays(),progress.maxTileNanos()/1e6),14,510);
        text(g, format("CPU: %d logical  JVM %s / system %s (whole-machine capacity)",
                hw.processors(), percent(hw.processCpu()), percent(hw.systemCpu())), 14, 418);
        text(g, format("Heap %s / %s MiB   RAM free %s / %s MiB",
                mib(hw.heapUsed()), mib(hw.heapMax()), mib(hw.freeMemory()), mib(hw.totalMemory())), 14, 436);
        text(g, format("GC since refresh: %s collections / %s ms   GPU: unused by CPU tracer",
                count(hw.gcCount()), count(hw.gcMillis())), 14, 454);
        var trace = latest.trace();
        if (trace == null) {
            text(g, "Trace CPU / allocations: n/a in this scene", 14, 472);
        } else {
            text(g, format("Last trace: aggregate CPU %s ms / wall %.2f ms; allocated %s MiB",
                    trace.cpuNanos() < 0 ? "n/a" : format("%.2f", trace.cpuNanos() / 1e6),
                    latest.rays() == null ? 0 : latest.rays().traceNanos() / 1e6, mib(trace.allocatedBytes())), 14, 472);
        }
        text(g, trace != null && trace.scene() != null
                ? format("%s: N=%d batch %d spp; total %,d spp %s; workers %d tile %d",
                        trace.scene(), trace.depth(), trace.samplesPerPixel(), trace.accumulatedSamples(),
                        trace.samplingStatus(), trace.workers(), trace.tileSize())
                : "Hardware counters refresh at 4 Hz", 14, 492);
    }

    private void traceDetails(Graphics2D g, List<FrameProfiler.Frame> frames) {
        text(g, "TRACE DRILLDOWN [F4]  JVM execution samples / rolling 10s", 14, 523);
        if (frames.getLast().rays() == null) {
            text(g, "No tracing in the active scene", 14, 545); return;
        }
        var sampling = profiler.traceSamples();
        var trace = frames.getLast().trace();
        if (trace != null) text(g, format("Glass: reflect %,d / transmit %,d; medium segments %,d",
                trace.dielectricReflections(), trace.dielectricTransmissions(), trace.absorptionSegments()), 14, 666);
        if (trace != null) text(g, format("Area samples %,d / emitter hits %,d / rough events %,d",
                trace.areaLightSamples(), trace.emitterHits(), trace.roughEvents()), 14, 684);
        if (trace != null) text(g, format("Volume flights %,d / scatters %,d / visibility segments %,d",
                trace.volumeSegments(), trace.volumeEvents(), trace.volumeVisibilitySegments()), 14, 702);
        long total = sampling.total();
        if (total == 0) { text(g, "Sampler: " + sampling.status(), 14, 545); return; }
        long[] totals = {sampling.generation(), sampling.intersection(), sampling.lighting(), sampling.shadow()};
        String[] names = {"Generation / transport / mean", "Nearest hit + materialize", "Lighting / light vectors", "Shadow intersection tests"};
        for (int i = 0; i < totals.length; i++) {
            text(g, format("%-29s %5.1f%%   %,d samples",
                    names[i], totals[i] * 100. / total, totals[i]), 14, 545 + i * 18);
        }
        var latest = frames.getLast();
        text(g, format("%,d samples%s; tests primary %,d / cont %,d / shadow %,d",
                total, total < 100 ? " (low sample count)" : "",
                latest.trace() == null ? 0 : latest.trace().primaryTests(),
                latest.trace() == null ? 0 : latest.trace().continuationTests(),
                latest.trace() == null ? 0 : latest.trace().shadowTests()), 14, 623);
        text(g, sampling.status().equals("active")
                ? "10ms sampling; batched delivery. Statistical CPU shares, not wall-time ms."
                : "Sampler: " + sampling.status(), 14, 644);
    }
    private static String percent(double n) { return n < 0 ? "n/a" : format("%.1f%%", n * 100); }
    private static String mib(long n) { return n < 0 ? "n/a" : format("%.1f", n / 1048576.); }
    private static String count(long n) { return n < 0 ? "n/a" : Long.toString(n); }
    private static String format(String pattern, Object... args) { return String.format(Locale.ROOT, pattern, args); }
    private static void text(Graphics2D g, String value, int x, int y) {
        g.setColor(new java.awt.Color(0xe5edf8));
        g.drawString(value, x, y);
    }
}
