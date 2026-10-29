package game;

import engine.CameraFocus;
import engine.FocusTargetSource;
import engine.WorldSnapshot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A single query owner with no queued views; geometry and scratch never belong to the GPU. */
final class GameAutofocus implements AutoCloseable {
    private final GameNavigation navigation;
    private final CameraFocus resolver = new CameraFocus();
    private final java.util.function.Supplier<WorldSnapshot> world;
    private final ScheduledExecutorService executor;
    private CameraFocus.Subject candidate;
    private long candidateSince;
    private long revision = -1;
    private long last;

    GameAutofocus(GameNavigation navigation, WorldSnapshot world) {
        this(navigation, () -> world);
    }

    GameAutofocus(GameNavigation navigation, java.util.function.Supplier<WorldSnapshot> world) {
        this.navigation = navigation;
        this.world = world;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "game-autofocus");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        engine.Camera camera;
        long epoch;
        synchronized (navigation) {
            if (!navigation.active() || !navigation.autofocus()) {
                candidate = null;
                last = 0;
                return;
            }
            camera = navigation.view().camera();
            epoch = navigation.focusRevision();
        }
        var scene = focusScene(world.get());
        long now = System.nanoTime();
        var result =
                resolver.resolve(
                        FocusTargetSource.CENTER, camera, scene, epoch, now, System::nanoTime);
        if (result.error() != null) {
            candidate = null;
            last = 0;
            return;
        }
        if (epoch != revision || !result.subject().equals(candidate)) {
            candidate = result.subject();
            candidateSince = now;
            revision = epoch;
        }
        // Wait 150 ms on a new subject; interpolate reciprocal distance with a 100 ms time
        // constant.
        double elapsed = last == 0 ? .05 : Math.min(.1, (now - last) / 1e9);
        last = now;
        if (now - candidateSince < 150_000_000L) {
            return;
        }
        double current = 1.0 / camera.focus();
        double target = 1.0 / result.distance();
        float next =
                Math.abs(target - current) <= .001 * target
                        ? result.distance()
                        : (float)
                                (1
                                        / (current
                                                + (target - current)
                                                        * (1 - Math.exp(-elapsed / .1))));
        if (!navigation.acceptFocus(result, next)) {
            candidate = null;
        }
    }

    // First-person weapon geometry must not steal focus from the aimed-at world surface.
    static CameraFocus.Scene focusScene(WorldSnapshot world) {
        return new CameraFocus.Scene(
                world.instances().stream()
                        .filter(instance -> !instance.name().startsWith("player/rifle/"))
                        .toList(),
                world.legacyObjects(),
                // Scene equality checks geometry; lighting revisions must not rebuild query data.
                0);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
