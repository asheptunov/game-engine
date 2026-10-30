package game;

import engine.Atmosphere;
import engine.LightingContinuity;

import math.Vec3;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns active game time and physics. Neither advancing time nor reading it calls the renderer. */
final class GameSimulation implements AutoCloseable {
    private static final AtomicLong NEXT_TIMELINE = new AtomicLong();

    record State(
            double hour,
            double duration,
            boolean running,
            GameLighting lighting,
            Vec3 solarDirection,
            LightingContinuity continuity) {
        GameLighting atmosphericLighting(Atmosphere.Parameters parameters) {
            return DayNightCycle.atmosphericLighting(solarDirection, parameters);
        }
    }

    private final DayNightCycle cycle = new DayNightCycle();
    private final long timeline = NEXT_TIMELINE.incrementAndGet();
    private final GameNavigation navigation;
    private final Supplier<ShooterGame> shooter;
    private final Consumer<Throwable> failure;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        var thread = new Thread(runnable, "sample-game-physics");
                        thread.setDaemon(true);
                        return thread;
                    });
    private long epoch;
    private double seconds;
    private volatile State state = snapshot();

    GameSimulation(
            GameNavigation navigation, Supplier<ShooterGame> shooter, Consumer<Throwable> failure) {
        this.navigation = navigation;
        this.shooter = shooter;
        this.failure = failure;
    }

    State state() {
        return state;
    }

    void start() {
        final long[] previous = {System.nanoTime()};
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.nanoTime();
                        double elapsed = (now - previous[0]) / 1e9;
                        previous[0] = now;
                        advance(elapsed, navigation.active());
                        var combat = shooter.get();
                        if (combat == null || combat.alive()) {
                            navigation.advance(elapsed);
                        } else {
                            navigation.advanceAim(elapsed);
                        }
                        if (combat != null) {
                            combat.playerHeight(navigation.bodyHeight());
                            combat.advance(
                                    elapsed,
                                    navigation.view().camera(),
                                    navigation.active() && navigation.captured());
                        }
                    } catch (Throwable error) {
                        navigation.active(false);
                        failure.accept(error);
                    }
                },
                0,
                4,
                TimeUnit.MILLISECONDS);
    }

    /** The simulation supplies elapsed seconds; tests can supply the same timeline at any rate. */
    synchronized void advance(double elapsed, boolean active) {
        if (!Double.isFinite(elapsed) || elapsed < 0) {
            throw new IllegalArgumentException("Simulation elapsed time must be nonnegative");
        }
        if (!active) {
            return;
        }
        seconds += elapsed;
        if (cycle.running() && elapsed > 0) {
            cycle.advance(elapsed);
            state = snapshot();
        }
    }

    synchronized void running(boolean value) {
        if (cycle.running() == value) {
            return;
        }
        cycle.running(value);
        discontinuity();
    }

    synchronized void hour(double value) {
        cycle.hour(value);
        discontinuity();
    }

    synchronized void duration(double value) {
        cycle.duration(value);
        state = snapshot();
    }

    synchronized void discontinuity() {
        epoch++;
        state = snapshot();
    }

    private State snapshot() {
        return new State(
                cycle.hour(),
                cycle.duration(),
                cycle.running(),
                cycle.lighting(),
                cycle.solarDirection(),
                cycle.running()
                        ? new LightingContinuity(timeline, epoch, seconds)
                        : LightingContinuity.NONE);
    }

    boolean closed() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
