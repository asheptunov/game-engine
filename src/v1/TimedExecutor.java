package v1;

import java.time.Clock;
import java.time.Duration;

public class TimedExecutor {
    private final Duration period;
    private final Clock clock;
    private final Runnable runnable;

    public TimedExecutor(int hertz, Clock clock, Runnable runnable) {
        this.period = Duration.ofSeconds(1).dividedBy(hertz);
        this.clock = clock;
        this.runnable = runnable;
    }

    public void execute() {
        var prev = clock.instant();
        while (true) {
            var cur = clock.instant();
            if (Duration.between(prev, cur).compareTo(period) < 0) {
                continue;
            }
            runnable.run();
            prev = cur;
        }
    }
}
