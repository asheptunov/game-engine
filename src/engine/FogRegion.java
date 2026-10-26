package engine;

import math.Vec3;

/**
 * A yaw-rotated box of air. Coefficients are inverse world units; pattern coordinates are
 * box-local.
 */
public record FogRegion(
        Vec3 center,
        Vec3 halfExtent,
        float yawDegrees,
        Vec3 scattering,
        Vec3 absorption,
        float density,
        float heightFalloff,
        float anisotropy,
        float variation,
        float frequency,
        float feather) {
    public FogRegion {
        java.util.Objects.requireNonNull(center);
        java.util.Objects.requireNonNull(halfExtent);
        java.util.Objects.requireNonNull(scattering);
        java.util.Objects.requireNonNull(absorption);
        for (int c = 0; c < 3; c++) {
            check(channel(center, c), -10000, 10000, "Fog center");
            check(channel(halfExtent, c), .25f, 50, "Fog half extent");
            check(channel(scattering, c), 0, .2f, "Fog scattering / world unit");
            check(channel(absorption, c), 0, .2f, "Fog absorption / world unit");
        }
        check(yawDegrees, -180, 180, "Fog yaw degrees");
        check(density, 0, 4, "Fog density scale");
        check(heightFalloff, 0, 8, "Fog falloff per normalized height");
        check(anisotropy, -.9f, .9f, "Fog anisotropy");
        check(variation, 0, 1, "Fog variation");
        check(frequency, 0, 8, "Fog pattern frequency");
        check(feather, 0, .49f, "Fog edge feather fraction");
    }

    private static void check(float v, float low, float high, String name) {
        if (!Float.isFinite(v) || v < low || v > high) {
            throw new IllegalArgumentException(
                    name + " must be finite and in " + low + ".." + high);
        }
    }

    static float channel(Vec3 v, int c) {
        return c == 0 ? v.x() : c == 1 ? v.y() : v.z();
    }

    public boolean active() {
        return density > 0
                && (scattering.x()
                                + scattering.y()
                                + scattering.z()
                                + absorption.x()
                                + absorption.y()
                                + absorption.z())
                        > 0;
    }

    public float sample(float x, float y, float z) {
        float cosine = (float) Math.cos(Math.toRadians(yawDegrees));
        float sine = (float) Math.sin(Math.toRadians(yawDegrees));
        float px = x - center.x();
        float pz = z - center.z();
        float u = (cosine * px - sine * pz) / halfExtent.x();
        float v = (y - center.y()) / halfExtent.y();
        float w = (sine * px + cosine * pz) / halfExtent.z();
        if (Math.max(Math.max(Math.abs(u), Math.abs(v)), Math.abs(w)) >= 1) {
            return 0;
        }
        float edge = 1;
        if (feather > 0) {
            float t =
                    Math.clamp(
                            (1 - Math.max(Math.max(Math.abs(u), Math.abs(v)), Math.abs(w)))
                                    / feather,
                            0,
                            1);
            edge = t * t * (3 - 2 * t);
        }
        double noise =
                .5
                        + .5
                                * Math.sin(u * frequency * Math.PI + .7)
                                * Math.sin(v * frequency * Math.PI + 1.3)
                                * Math.sin(w * frequency * Math.PI + 2.1);
        return (float)
                (density
                        * edge
                        * Math.exp(-heightFalloff * (v + 1) * .5)
                        * (1 - variation + variation * noise));
    }

    private static boolean clipAxis(
            double origin, double direction, double extent, double[] result) {
        if (Math.abs(direction) < 1e-12) {
            return Math.abs(origin) < extent;
        }
        double a = (-extent - origin) / direction;
        double b = (extent - origin) / direction;
        result[0] = Math.max(result[0], Math.min(a, b));
        result[1] = Math.min(result[1], Math.max(a, b));
        return result[1] > result[0];
    }

    /** Clipped ray interval in world units. Parallel rays never divide by zero. */
    public boolean interval(
            float x, float y, float z, float dx, float dy, float dz, float limit, double[] result) {
        float cosine = (float) Math.cos(Math.toRadians(yawDegrees));
        float sine = (float) Math.sin(Math.toRadians(yawDegrees));
        float px = x - center.x();
        float pz = z - center.z();
        double lo = 0;
        double hi = limit;
        for (int c = 0; c < 3; c++) {
            double o =
                    c == 0
                            ? (cosine * px - sine * pz)
                            : c == 1 ? y - center.y() : sine * px + cosine * pz;
            double d = c == 0 ? cosine * dx - sine * dz : c == 1 ? dy : sine * dx + cosine * dz;
            double e = channel(halfExtent, c);
            result[0] = lo;
            result[1] = hi;
            if (!clipAxis(o, d, e, result)) {
                return false;
            }
            lo = result[0];
            hi = result[1];
        }
        result[0] = lo;
        result[1] = hi;
        return hi > lo;
    }
}
