package game;

import engine.SceneInstance;
import engine.WorldSnapshot;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded host-only history. Never calls renderer or navigation while holding its monitor. */
final class GameRecording {
    static final long WINDOW_NANOS = 30_000_000_000L;
    static final int MAX_MOTIONS = 8192;
    static final int MAX_STATES = 2048;
    static final int MAX_PICTURES = 8;

    record Motion(
            long nanos,
            double seconds,
            GameNavigation.State before,
            GameNavigation.State after,
            List<SceneInstance> collision) {}

    record Observation(
            long nanos,
            WorldSnapshot world,
            GameCapture.Controls controls,
            GameNavigation.State navigation) {}

    record Picture(long nanos, GameRenderer.Frame frame) {}

    record Frozen(
            List<Motion> motions, List<Observation> states, List<Picture> pictures, long dropped) {}

    private final ArrayDeque<Motion> motions = new ArrayDeque<>();
    private final ArrayDeque<Observation> states = new ArrayDeque<>();
    private final ArrayDeque<Picture> pictures = new ArrayDeque<>();
    private long dropped;

    synchronized void motion(
            long now,
            double seconds,
            GameNavigation.State before,
            GameNavigation.State after,
            List<SceneInstance> collision) {
        motions.addLast(new Motion(now, seconds, before, after, collision));
        while (!motions.isEmpty() && motions.getFirst().nanos() < now - WINDOW_NANOS) {
            motions.removeFirst();
        }
        while (motions.size() > MAX_MOTIONS) {
            motions.removeFirst();
            dropped++;
        }
    }

    synchronized void observe(Observation observation, boolean force) {
        var last = states.peekLast();
        if (last != null
                && last.world() == observation.world()
                && last.controls().equals(observation.controls())) {
            return;
        }
        if (!force && last != null && observation.nanos() - last.nanos() < 20_000_000L) {
            return;
        }
        states.addLast(observation);
        while (!states.isEmpty()
                && states.getFirst().nanos() < observation.nanos() - WINDOW_NANOS) {
            states.removeFirst();
        }
        while (states.size() > MAX_STATES) {
            states.removeFirst();
            dropped++;
        }
    }

    synchronized void picture(GameRenderer.Frame frame) {
        long now = System.nanoTime();
        if (!pictures.isEmpty() && now - pictures.getLast().nanos() < 4_000_000_000L) {
            return;
        }
        pictures.addLast(new Picture(now, frame));
        while (pictures.size() > MAX_PICTURES) {
            pictures.removeFirst();
        }
    }

    synchronized Frozen freeze(long now, boolean history) {
        if (!history) {
            return new Frozen(List.of(), List.of(), List.of(), dropped);
        }
        return new Frozen(
                motions.stream().filter(value -> value.nanos() >= now - WINDOW_NANOS).toList(),
                states.stream().filter(value -> value.nanos() >= now - WINDOW_NANOS).toList(),
                pictures.stream().filter(value -> value.nanos() >= now - WINDOW_NANOS).toList(),
                dropped);
    }
}
