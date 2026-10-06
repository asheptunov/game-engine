package engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Daemon fixed-step application clock, deliberately independent of rendering and presentation. */
public final class FixedStepLoop implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final AtomicLong ticks=new AtomicLong();
    private volatile Throwable failure;

    public FixedStepLoop(Duration step, LongConsumer update) {
        Objects.requireNonNull(step);Objects.requireNonNull(update);
        long nanos=step.toNanos();
        if(nanos<=0)throw new IllegalArgumentException("Update step must be positive");
        executor=Executors.newSingleThreadScheduledExecutor(r->{
            var thread=new Thread(r,"engine-fixed-update");thread.setDaemon(true);return thread;
        });
        executor.scheduleAtFixedRate(()->{
            try {update.accept(ticks.incrementAndGet());}
            catch(Throwable error){failure=error;throw error;}
        },0,nanos,TimeUnit.NANOSECONDS);
    }

    public long ticks(){return ticks.get();}
    public Throwable failure(){return failure;}
    @Override public void close(){executor.shutdownNow();}
}
