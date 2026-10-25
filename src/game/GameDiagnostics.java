package game;

import engine.RenderProgress;
import engine.RenderSettings;
import engine.RenderView;
import engine.WorldSnapshot;

import java.time.Instant;
import java.util.ArrayDeque;

/** Host-only snapshots; reporting never waits for the renderer or queries a failed device. */
final class GameDiagnostics {
    record State(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            GameLighting lighting,
            double hour,
            double dayDuration,
            boolean dayCycle,
            float exposure,
            boolean active,
            RenderProgress progress,
            GameRenderer.Frame completed,
            String backendStatus) {}

    private volatile State latest;
    private final ArrayDeque<String> events = new ArrayDeque<>();

    void capture(State next) {
        var previous = latest;
        latest = next;
        if (requested(previous) != requested(next)) {
            event("render requested generation=" + requested(next));
        }
        if (next.completed() != null
                && (previous == null
                        || previous.completed() == null
                        || previous.completed().generation() != next.completed().generation())) {
            event(
                    "render completed generation="
                            + next.completed().generation()
                            + ", samples="
                            + next.completed().samples());
        }
        if (previous == null
                || previous.settings() != next.settings()
                || previous.world() != next.world()
                || previous.active() != next.active()
                || previous.exposure() != next.exposure()) {
            event(
                    "control/world update: revision="
                            + next.world().revision()
                            + ", active="
                            + next.active()
                            + ", exposure="
                            + next.exposure()
                            + ", settings="
                            + next.settings()
                            + ", hour="
                            + next.hour());
        }
    }

    private static long requested(State state) {
        return state == null || state.progress() == null
                ? -1
                : state.progress().requestedGeneration();
    }

    synchronized void event(String event) {
        if (events.size() == 64) {
            events.removeFirst();
        }
        events.addLast(Instant.now() + " " + event);
    }

    State latest() {
        return latest;
    }

    synchronized String events() {
        return String.join("\n", events);
    }
}
