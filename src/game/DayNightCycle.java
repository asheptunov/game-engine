package game;

import engine.Sky;

import math.Vec3;

/** Active game time only; one complete day takes ten real minutes by default. */
public final class DayNightCycle {
    private double hour = 9;
    private double duration = 600;
    private boolean running;

    public double hour() {
        return hour;
    }

    public double duration() {
        return duration;
    }

    public boolean running() {
        return running;
    }

    public void running(boolean value) {
        running = value;
    }

    public void hour(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 24) {
            throw new IllegalArgumentException("Hour must be 0..24");
        }
        hour = value % 24;
    }

    public void duration(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 30 || seconds > 7200) {
            throw new IllegalArgumentException("Day duration must be 30..7200 seconds");
        }
        duration = seconds;
    }

    public void advance(double seconds) {
        if (running && Double.isFinite(seconds) && seconds > 0) {
            hour = (hour + seconds * 24 / duration) % 24;
        }
    }

    public Vec3 solarDirection() {
        double angle = (hour - 6) * Math.PI / 12;
        return new Vec3((float) Math.cos(angle), (float) Math.sin(angle), .3f);
    }

    public GameLighting lighting() {
        double angle = (hour - 6) * Math.PI / 12;
        float altitude = (float) Math.sin(angle);
        float daylight = Math.clamp((altitude + .12f) / .45f, 0, 1);
        float sun = Math.max(0, altitude);
        // A single directional source meets the GPU contract: sunlight by day, moonlight at night.
        float sign = altitude >= 0 ? 1 : -1;
        var direction =
                new Vec3((float) Math.cos(angle) * sign, Math.max(.02f, Math.abs(altitude)), .3f);
        var color =
                altitude >= 0
                        ? blend(
                                new Vec3(1, .35f, .12f),
                                new Vec3(1, .95f, .85f),
                                Math.min(1, sun * 3))
                        : new Vec3(.45f, .6f, 1);
        var sky =
                new Sky(
                        blend(new Vec3(.006f, .009f, .02f), new Vec3(.3f, .4f, .55f), daylight),
                        blend(new Vec3(.002f, .004f, .012f), new Vec3(.12f, .3f, .65f), daylight));
        return new GameLighting(
                GameLighting.Preset.NOON,
                true,
                direction,
                color,
                altitude >= 0 ? sun * 3 : Math.abs(altitude) * .08f,
                sky);
    }

    /** The signed solar direction survives night; the explicit directional light is moonlight. */
    public GameLighting atmosphericLighting(engine.Atmosphere.Parameters parameters) {
        return atmosphericLighting(solarDirection(), parameters);
    }

    static GameLighting atmosphericLighting(Vec3 solar, engine.Atmosphere.Parameters parameters) {
        var air = new engine.Atmosphere(parameters, solar, new Vec3(3, 3, 3));
        float night = Math.clamp(-air.solarDirection().y() / .2f, 0, 1);
        var moon = air.solarDirection().scale(-1);
        var moonColor = new Vec3(.45f, .6f, 1);
        float moonStrength = Math.max(0, moon.y()) * .08f;
        var sky =
                new Sky(
                        new Vec3(.006f, .009f, .02f).scale(night),
                        new Vec3(.002f, .004f, .012f).scale(night),
                        moon.y() > 0
                                ? new Sky.Disc(
                                        moon, moonColor.scale(Math.max(1, moonStrength * 4)), .75f)
                                : null,
                        air);
        return new GameLighting(GameLighting.Preset.NOON, true, moon, moonColor, moonStrength, sky);
    }

    private static Vec3 blend(Vec3 from, Vec3 to, float amount) {
        return new Vec3(
                from.x() + (to.x() - from.x()) * amount,
                from.y() + (to.y() - from.y()) * amount,
                from.z() + (to.z() - from.z()) * amount);
    }
}
