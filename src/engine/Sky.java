package engine;

import math.Vec3;

/** Immutable linear radiance. Interpolate from nadir (-Y) to zenith (+Y); horizon is halfway. */
public record Sky(Vec3 nadir, Vec3 zenith) {
    public static final Sky BLACK = new Sky(Vec3.ZERO, Vec3.ZERO);

    public Sky {
        if (nadir == null || zenith == null) {
            throw new IllegalArgumentException("Sky colors are required");
        }
        for (var color : new Vec3[] {nadir, zenith}) {
            for (float channel : new float[] {color.x(), color.y(), color.z()}) {
                if (!Float.isFinite(channel) || channel < 0) {
                    throw new IllegalArgumentException(
                            "Sky radiance must be finite and nonnegative");
                }
            }
        }
    }

    /** Unit world-space ray direction; caller owns the reusable RGB output. */
    public void sample(float directionY, float[] rgb) {
        float weight = Math.clamp((directionY + 1) * .5f, 0, 1);
        rgb[0] = nadir.x() * (1 - weight) + zenith.x() * weight;
        rgb[1] = nadir.y() * (1 - weight) + zenith.y() * weight;
        rgb[2] = nadir.z() * (1 - weight) + zenith.z() * weight;
    }
}
