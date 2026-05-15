package scenes.viewport;

/**
 * Accumulates per-frame {@link BackwardRayTracer.TraceStats} into a fixed-window summary. Caller drives
 * the cadence: feed every frame via {@link #record}, check {@link #frames()} against a target, read
 * {@link #summary()} and {@link #reset()}. Sums use {@code long} to avoid overflow across thousands of frames.
 */
public class TraceStatsAggregator {
    private int  frames;
    private long elapsedNanosSum;
    private long minElapsedNanos = Long.MAX_VALUE;
    private long maxElapsedNanos = Long.MIN_VALUE;
    private long primaryHitsSum;
    private long shadowRaysSum;
    private long occludedShadowRaysSum;
    private long litPixelsSum;

    public record Summary(int frames,
                          double avgElapsedMs,
                          double minElapsedMs,
                          double maxElapsedMs,
                          long avgPrimaryHits,
                          long avgShadowRays,
                          long avgOccludedShadowRays,
                          long avgLitPixels) {}

    public void record(BackwardRayTracer.TraceStats s) {
        frames++;
        elapsedNanosSum       += s.elapsedNanos();
        minElapsedNanos        = Math.min(minElapsedNanos, s.elapsedNanos());
        maxElapsedNanos        = Math.max(maxElapsedNanos, s.elapsedNanos());
        primaryHitsSum        += s.primaryHits();
        shadowRaysSum         += s.shadowRays();
        occludedShadowRaysSum += s.occludedShadowRays();
        litPixelsSum          += s.litPixels();
    }

    public int frames() { return frames; }

    public Summary summary() {
        return new Summary(frames,
                elapsedNanosSum / 1_000_000.0 / frames,
                minElapsedNanos / 1_000_000.0,
                maxElapsedNanos / 1_000_000.0,
                primaryHitsSum / frames,
                shadowRaysSum / frames,
                occludedShadowRaysSum / frames,
                litPixelsSum / frames);
    }

    public void reset() {
        frames = 0;
        elapsedNanosSum = 0;
        minElapsedNanos = Long.MAX_VALUE;
        maxElapsedNanos = Long.MIN_VALUE;
        primaryHitsSum = 0;
        shadowRaysSum = 0;
        occludedShadowRaysSum = 0;
        litPixelsSum = 0;
    }
}
