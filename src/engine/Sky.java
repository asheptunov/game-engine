package engine;

import math.Vec3;

/** Immutable linear radiance. Interpolate from nadir (-Y) to zenith (+Y); horizon is halfway. */
public record Sky(Vec3 nadir, Vec3 zenith, Disc disc) {
    public static final Sky BLACK = new Sky(Vec3.ZERO, Vec3.ZERO);

    /**
     * A distant, camera-visible disc. Radius is angular degrees, independent of camera position.
     */
    public record Disc(Vec3 direction, Vec3 radiance, float radiusDegrees) {
        public Disc {
            // Reuse the light's finite/nonzero direction validation and normalization.
            direction =
                    new engine.lights.DirectionalLight(direction, new Vec3(1, 1, 1), 0).direction();
            if (!Float.isFinite(radiusDegrees) || radiusDegrees <= 0 || radiusDegrees > 10) {
                throw new IllegalArgumentException(
                        "Sky disc radius must be greater than 0 and at most 10 degrees");
            }
            new Sky(radiance, radiance);
        }

        public float cosineRadius() {
            return (float) Math.cos(Math.toRadians(radiusDegrees));
        }
    }

    public Sky(Vec3 nadir, Vec3 zenith) {
        this(nadir, zenith, null);
    }

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

    /** Only camera misses see the disc; diffuse bounces keep the gradient and explicit light. */
    public void sample(float x, float y, float z, boolean cameraRay, float[] rgb) {
        sample(y, rgb);
        if (cameraRay
                && disc != null
                && x * disc.direction.x() + y * disc.direction.y() + z * disc.direction.z()
                        >= disc.cosineRadius()) {
            rgb[0] = disc.radiance.x();
            rgb[1] = disc.radiance.y();
            rgb[2] = disc.radiance.z();
        }
    }
}
