package timing;

import logging.LogManager;
import logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.DoubleStream;

public class PeriodicExecutor {
    private static final Logger LOG             = LogManager.instance().getThis();
    private static final int    LOOKBEHIND_SIZE = 100;

    private final Duration period;
    private final Clock    clock;
    private final Runnable runnable;
    private final Stats    stats;

    public PeriodicExecutor(int hertz, Clock clock, Runnable runnable) {
        this.period = Duration.ofSeconds(1).dividedBy(hertz);
        this.clock = clock;
        this.runnable = runnable;
        this.stats = new Stats(LOOKBEHIND_SIZE);
        LOG.info("Will execute %s at a frequency of %d hz (period of %s)", runnable, hertz, period);
        LOG.info("Using a lookbehind window of %d executions for drift correction", LOOKBEHIND_SIZE);
    }

    public void execute() throws InterruptedException {
        var prev = clock.instant();
        runnable.run();
        //noinspection InfiniteLoopStatement
        while (true) {
            var now = clock.instant();
            var wait = (long) (period.minus(Duration.between(prev, now)).toMillis() - stats.driftMs);
            if (wait > 0) {
                LOG.debug("sleeping %d ms (adjusted %+.2f ms for drift)", wait, stats.driftMs);
                //noinspection BusyWait
                Thread.sleep(wait);
                now = clock.instant();  // update if we waited
            }
            runnable.run();
            stats.add(now);
            prev = now;
        }
    }

    private class Stats {
        private final Deque<Instant>  rWindow       = new LinkedList<>();
        private final Deque<Duration> dWindow       = new LinkedList<>();
        private final int             maxSize;
        private       double          periodMeanMs  = period.toMillis();
        private       double          periodHzMean  = 1_000. / periodMeanMs;
        private       double          periodMsStdev = 0.;
        private       double          driftMs       = 0.;
        private       double          driftPct      = 0.;

        private Stats(int maxSize) {
            this.maxSize = maxSize;
            //noinspection resource
            new ScheduledThreadPoolExecutor(1)
                    .scheduleAtFixedRate(this::log, 0, 30, TimeUnit.SECONDS);
        }

        private void add(Instant instant) {
            if (rWindow.size() > 1) {
                dWindow.add(Duration.between(rWindow.peekLast(), instant));
            }
            rWindow.add(instant);
            if (rWindow.size() > maxSize) {
                rWindow.poll();
                dWindow.poll();
            }
            recompute();
        }

        private void log() {
            LOG.info("mean %.2f ms between runs (%.2f hz). std deviation %.2f ms. drift %+.2f ms (%+.2f%%).",
                    periodMeanMs, periodHzMean, periodMsStdev, driftMs, driftPct);
        }

        private void recompute() {
            assert !rWindow.isEmpty();
            periodMeanMs = Duration.between(rWindow.peek(), rWindow.getLast()).toMillis() / (rWindow.size() - 1.);
            periodHzMean = 1_000. / periodMeanMs;
            periodMsStdev = stdev(dWindow.stream().mapToDouble(Duration::toMillis), periodMeanMs);
            driftMs = periodMeanMs - period.toMillis();
            driftPct = 100. * driftMs / (period.toMillis());
        }

        private static double stdev(DoubleStream samples, double mean) {
            return Math.sqrt(samples
                    .map(s -> Math.pow(s - mean, 2))
                    .average().orElse(0.));
        }
    }
}
