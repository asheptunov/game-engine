package game;

import engine.Sky;
import engine.lights.DirectionalLight;

import math.Vec3;

/** Diagnostic fixed sun directions; the game has no advancing time-of-day clock. */
public record GameLighting(Preset preset, boolean skyEnabled) {
    public enum Preset {
        MORNING(new Vec3(-1, .45f, -.6f)),
        NOON(new Vec3(-.3f, 1, -.4f)),
        EVENING(new Vec3(1, .45f, .6f));

        private final Vec3 direction;

        Preset(Vec3 direction) {
            this.direction = direction;
        }
    }

    private static final Sky BLUE = new Sky(new Vec3(.3f, .4f, .55f), new Vec3(.12f, .3f, .65f));

    public GameLighting {
        java.util.Objects.requireNonNull(preset);
    }

    public static GameLighting defaults() {
        return new GameLighting(Preset.NOON, true);
    }

    public DirectionalLight sun() {
        return new DirectionalLight(preset.direction, new Vec3(1, .94f, .84f), 3);
    }

    public Sky sky() {
        return skyEnabled ? BLUE : Sky.BLACK;
    }
}
