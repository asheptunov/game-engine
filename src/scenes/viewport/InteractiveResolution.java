package scenes.viewport;

/** Live-monitor-owned policy. It never changes the camera or borrows samples from another grid. */
final class InteractiveResolution {
    static final long SETTLE_NANOS = 350_000_000L;
    static final long CHANGE_NANOS = 500_000_000L;
    private static final double[] SCALES = {1, .75, .5, .375, .25, .1875, .125, .09375, .0625};
    private Camera.Identity camera;
    private long lastMotion = Long.MIN_VALUE, lastChange;
    private double nanosPerPixel;
    private int fastUpdates;
    private long revision, consideredRevision;

    void observe(ViewportState state, long now) {
        var next=state.camera().identity();
        if(camera!=null && !camera.equals(next))lastMotion=now;
        camera=next;
    }
    boolean moving(long now) { return lastMotion != Long.MIN_VALUE && now - lastMotion < SETTLE_NANOS; }
    void completed(int width, int height, long elapsed) {
        if (elapsed <= 0) return;
        double cost = (double)elapsed / ((long)width * height);
        nanosPerPixel = nanosPerPixel == 0 ? cost : .75 * nanosPerPixel + .25 * cost;
        revision++;
    }
    void choose(ViewportState state, long now) {
        int w = state.sensorPixelsW(), h = state.sensorPixelsH();
        boolean temporalBudget=state.temporalBudgetSupported();
        boolean enabled=state.interactive() || temporalBudget;
        if (!enabled || !moving(now)) {
            if (!state.paused() || !enabled) state.sampledResolution(w,h);
            fastUpdates = 0;
            return;
        }
        if (state.paused()) return;
        if(!state.interactive()) {
            // Fixed opt-in ceiling: no feedback-driven grid oscillation or cross-grid reuse.
            double scale=Math.max(state.motionScale(),Math.max(64./w,64./h));
            state.sampledResolution((int)Math.round(w*scale),(int)Math.round(h*scale));
            return;
        }
        boolean freshCost = revision != consideredRevision;
        consideredRevision = revision;
        double min = state.minimumScale();
        double current = (double)state.sampledWidth() / w;
        double budget = state.interactiveMillis() * 1e6;
        // Reserve 20% for display pacing/overlays/presentation beyond the observed image cost.
        double wanted = nanosPerPixel == 0 ? .5 : Math.sqrt(.8 * budget / (nanosPerPixel * w * h));
        double next = min;
        for (double scale : SCALES) if (scale >= min && scale <= wanted) { next = scale; break; }
        next = Math.clamp(next, min, 1);
        if(temporalBudget) next=Math.min(next,Math.max(min,state.motionScale()));
        double predicted = nanosPerPixel * state.sampledWidth() * state.sampledHeight();
        boolean outsideBounds = current < min || (temporalBudget && current>Math.max(min,state.motionScale()));
        if (!outsideBounds && next < current && nanosPerPixel != 0 && predicted <= budget * 1.25) return;
        if (!outsideBounds && next > current) {
            if (predicted >= budget * .65) { fastUpdates = 0; return; }
            if (!freshCost || ++fastUpdates < 3 || now - lastChange < CHANGE_NANOS) return;
        } else fastUpdates = 0;
        if (!outsideBounds && nanosPerPixel != 0 && now - lastChange < CHANGE_NANOS) return;
        int nextW = (int)Math.round(w * next), nextH = (int)Math.round(h * next);
        if (nextW != state.sampledWidth() || nextH != state.sampledHeight()) {
            state.sampledResolution(nextW,nextH); lastChange = now; fastUpdates = 0;
        }
    }
}
