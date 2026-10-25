package game;

import engine.Sky;
import engine.lights.DirectionalLight;

import math.Vec3;

/** Directional sunlight or moonlight plus the matching visible sky disc. */
public record GameLighting(
        Preset preset,
        boolean skyEnabled,
        Vec3 direction,
        Vec3 sunColor,
        float intensity,
        Sky skyColors) {
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
        java.util.Objects.requireNonNull(skyColors);
        new DirectionalLight(direction, sunColor, intensity);
    }

    public GameLighting(Preset preset, boolean skyEnabled) {
        this(preset, skyEnabled, preset.direction, new Vec3(1, .94f, .84f), 3, BLUE);
    }

    public GameLighting withPreset(Preset value) {
        return new GameLighting(value, skyEnabled, value.direction, sunColor, intensity, skyColors);
    }

    public GameLighting withSky(boolean enabled) {
        return new GameLighting(preset, enabled, direction, sunColor, intensity, skyColors);
    }

    public static GameLighting defaults() {
        return new GameLighting(Preset.NOON, true);
    }

    public DirectionalLight sun() {
        return new DirectionalLight(direction, sunColor, intensity);
    }

    public Sky sky() {
        return skyEnabled
                ? new Sky(
                        skyColors.nadir(),
                        skyColors.zenith(),
                        new Sky.Disc(direction, sunColor.scale(Math.max(1, intensity * 4)), .75f))
                : Sky.BLACK;
    }
}
