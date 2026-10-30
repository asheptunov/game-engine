package engine;

/**
 * Simulation provenance for gradually changing illumination. Seconds are active simulation time,
 * never frame counts. A new epoch marks a discontinuity such as a manual time jump.
 */
public record LightingContinuity(long timeline, long epoch, double seconds) {
    public static final LightingContinuity NONE = new LightingContinuity(0, 0, 0);

    public LightingContinuity {
        if (timeline < 0 || epoch < 0 || !Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Invalid lighting timeline");
        }
    }

    public boolean follows(LightingContinuity previous) {
        double elapsed = seconds - previous.seconds;
        return timeline != 0
                && timeline == previous.timeline
                && epoch == previous.epoch
                && elapsed >= 0
                && elapsed <= .5;
    }
}
