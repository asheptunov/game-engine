package gpu;

import engine.RenderSettings;
import engine.RenderView;

/** Session-monitor-owned grid selection. Costs include trace, readback and consumer copying. */
final class GpuResolutionPolicy {
    private static final long SETTLE_NANOS = 350_000_000L;
    private static final long COOLDOWN_NANOS = 500_000_000L;
    private static final double[] SCALES = {1, .875, .75, .625, .5, .375, .25};
    private long lastMotion = Long.MIN_VALUE;
    private long lastChange;
    private long measured;
    private long considered;
    private int fastImages;
    private double nanosPerPixel;
    private RenderView previous;
    private RenderView selected;

    void observe(RenderView view, long now) {
        if (previous != null && !previous.camera().identity().equals(view.camera().identity())) {
            lastMotion = now;
        }
        if (previous != null
                && (previous.width() != view.width() || previous.height() != view.height())) {
            reset();
        }
        previous = view;
    }

    void reset() {
        lastMotion = Long.MIN_VALUE;
        lastChange = 0;
        nanosPerPixel = 0;
        fastImages = 0;
        measured = considered = 0;
        selected = null;
        previous = null;
    }

    boolean moving(long now) {
        return lastMotion != Long.MIN_VALUE && now - lastMotion < SETTLE_NANOS;
    }

    void completed(int width, int height, long elapsed) {
        if (elapsed <= 0) {
            return;
        }
        double cost = (double) elapsed / ((long) width * height);
        nanosPerPixel = nanosPerPixel == 0 ? cost : .75 * nanosPerPixel + .25 * cost;
        measured++;
    }

    RenderView choose(RenderView requested, RenderSettings settings, long now) {
        if (!settings.interactive() || !moving(now)) {
            selected = requested;
            fastImages = 0;
            return requested;
        }
        int width = requested.width();
        int height = requested.height();
        double minimum = minimumScale(requested, settings);
        double current = selected == null ? 1 : (double) selected.width() / width;
        double budget = settings.interactiveMillis() * 1e6;
        double next = desiredScale(width, height, minimum, budget);
        next = stabilizedScale(current, next, minimum, budget, width, height, now);
        if (next != current) {
            lastChange = now;
            fastImages = 0;
        }
        selected =
                new RenderView(
                        requested.camera(),
                        (int) Math.round(width * next),
                        (int) Math.round(height * next));
        return selected;
    }

    private static double minimumScale(RenderView requested, RenderSettings settings) {
        double scale =
                settings.minimumWidth() == 0
                        ? .25
                        : Math.max(
                                (double) settings.minimumWidth() / requested.width(),
                                (double) settings.minimumHeight() / requested.height());
        return Math.min(
                1, Math.max(scale, Math.max(64. / requested.width(), 64. / requested.height())));
    }

    private double desiredScale(int width, int height, double minimum, double budget) {
        // Leave 20% for Swing/native presentation and scheduling outside this session.
        double wanted =
                nanosPerPixel == 0 ? .5 : Math.sqrt(.8 * budget / (nanosPerPixel * width * height));
        for (double scale : SCALES) {
            if (scale >= minimum && scale <= wanted) {
                return scale;
            }
        }
        return minimum;
    }

    private double stabilizedScale(
            double current,
            double next,
            double minimum,
            double budget,
            int width,
            int height,
            long now) {
        boolean fresh = measured != considered;
        considered = measured;
        if (current < minimum) {
            return next;
        }
        double predicted = nanosPerPixel * width * height * current * current;
        if (nanosPerPixel != 0 && now - lastChange < COOLDOWN_NANOS) {
            return current;
        }
        if (next < current && nanosPerPixel != 0 && predicted <= budget) {
            return current;
        }
        if (next > current) {
            if (predicted >= budget * .65) {
                fastImages = 0;
            } else if (fresh) {
                fastImages++;
            }
            if (fastImages < 3) {
                return current;
            }
        }
        return next;
    }
}
