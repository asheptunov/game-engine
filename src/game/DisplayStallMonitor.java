package game;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import javax.swing.SwingUtilities;

/** Samples stalled display threads from a separate daemon; never waits for a renderer lock. */
final class DisplayStallMonitor implements AutoCloseable {
    private final java.util.concurrent.ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        var thread = new Thread(runnable, "game-display-watchdog");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ArrayDeque<String> stalls = new ArrayDeque<>();
    private final Consumer<String> report;
    private final LongSupplier publication;
    private volatile long heartbeat = System.nanoTime();
    private volatile long paint;
    private volatile long shown;
    private volatile long fresh;
    private volatile boolean active;
    private long lastReport;

    DisplayStallMonitor(LongSupplier publication, Consumer<String> report) {
        this.publication = publication;
        this.report = report;
        worker.scheduleWithFixedDelay(this::poll, 250, 250, TimeUnit.MILLISECONDS);
    }

    void painted(long value, boolean active) {
        if (shown != value || !active) {
            fresh = System.nanoTime();
        }
        this.active = active;
        shown = value;
        paint = System.nanoTime();
    }

    private void poll() {
        long now = System.nanoTime();
        long published = publication.getAsLong();
        boolean blocked = now - heartbeat > 1_000_000_000L;
        boolean unpainted = paint != 0 && published > shown && now - paint > 1_000_000_000L;
        boolean stale = active && fresh != 0 && now - fresh > 1_000_000_000L;
        if ((blocked || unpainted || stale) && now - lastReport > 1_000_000_000L) {
            lastReport = now;
            String text =
                    java.time.Instant.now()
                            + " heartbeatAgeMs="
                            + (now - heartbeat) / 1_000_000
                            + " paintAgeMs="
                            + (paint == 0 ? -1 : (now - paint) / 1_000_000)
                            + " latestPublication="
                            + published
                            + " freshAgeMs="
                            + (fresh == 0 ? -1 : (now - fresh) / 1_000_000)
                            + " shownPublication="
                            + shown;
            stalls.addLast(text + stacks());
            while (stalls.size() > 8) {
                stalls.removeFirst();
            }
            report.accept(
                    "Last eight display stalls exceeding one second. Times use UTC; durations use"
                            + " monotonic time.\n"
                            + String.join("\n", stalls));
        }
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(
                    () -> {
                        heartbeat = System.nanoTime();
                        pending.set(false);
                    });
        }
    }

    private static String stacks() {
        var result = new StringBuilder();
        for (var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            String name = thread.getThreadName();
            if (!name.contains("AWT")
                    && !name.contains("game")
                    && !name.contains("gpu")
                    && !name.contains("Java2D")) {
                continue;
            }
            result.append("\n")
                    .append(name)
                    .append(" ")
                    .append(thread.getThreadState())
                    .append(" lock=")
                    .append(thread.getLockName())
                    .append(" owner=")
                    .append(thread.getLockOwnerName());
            var stack = thread.getStackTrace();
            for (int i = 0; i < Math.min(48, stack.length); i++) {
                result.append("\n  at ").append(stack[i]);
            }
            result.append('\n');
        }
        return result.toString();
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
