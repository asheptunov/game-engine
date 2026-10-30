package gpu;

import engine.Atmosphere;
import engine.Sky;
import engine.WorldSnapshot;
import engine.lights.DirectionalLight;

import math.Vec3;

/** Conservative history admission for simulation-tagged, gradually changing distant lighting. */
final class GpuLightingHistory {
    private static final float MIN_DIRECTION_DOT = (float) Math.cos(Math.toRadians(2));

    private GpuLightingHistory() {}

    static float confidence(WorldSnapshot before, WorldSnapshot after) {
        var previous = before.lightingContinuity();
        var next = after.lightingContinuity();
        boolean animated = previous.timeline() != 0 || next.timeline() != 0;
        if (animated && !next.follows(previous)) {
            return 0;
        }
        if (before.sky().equals(after.sky()) && before.lights().equals(after.lights())) {
            return 1;
        }
        if (!animated
                || !similarSky(before.sky(), after.sky())
                || before.lights().size() != after.lights().size()) {
            return 0;
        }
        for (int i = 0; i < before.lights().size(); i++) {
            if (!(before.lights().get(i) instanceof DirectionalLight a)
                    || !(after.lights().get(i) instanceof DirectionalLight b)
                    || !direction(a.direction(), b.direction())
                    || !radiance(a.color().scale(a.strength()), b.color().scale(b.strength()))) {
                return 0;
            }
        }
        // Bound temporal inertia during animation. Local radiance clamping/NRD anti-lag still
        // responds to shadow changes; geometry and disocclusion validation remain independent.
        return .8f;
    }

    private static boolean similarSky(Sky a, Sky b) {
        return a.fog().equals(b.fog())
                && radiance(a.nadir(), b.nadir())
                && radiance(a.zenith(), b.zenith())
                && disc(a.disc(), b.disc())
                && atmosphere(a.atmosphere(), b.atmosphere());
    }

    private static boolean disc(Sky.Disc a, Sky.Disc b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.radiusDegrees() == b.radiusDegrees()
                && direction(a.direction(), b.direction())
                && radiance(a.radiance(), b.radiance());
    }

    private static boolean atmosphere(Atmosphere a, Atmosphere b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.parameters().equals(b.parameters())
                && direction(a.solarDirection(), b.solarDirection())
                && radiance(a.solarIrradiance(), b.solarIrradiance());
    }

    private static boolean direction(Vec3 a, Vec3 b) {
        return a.dot(b) >= MIN_DIRECTION_DOT;
    }

    private static boolean radiance(Vec3 a, Vec3 b) {
        return channel(a.x(), b.x()) && channel(a.y(), b.y()) && channel(a.z(), b.z());
    }

    private static boolean channel(float a, float b) {
        return Math.abs(a - b) <= .25f * (.02f + Math.max(a, b));
    }
}
