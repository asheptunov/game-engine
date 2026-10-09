package engine.lights;

import math.Ray;
import math.Vec3;

import java.util.List;
import java.util.random.RandomGenerator;

/** Distant source. Direction points from the surface toward the light, not along photon travel. */
public record DirectionalLight(Vec3 direction, Vec3 color, float strength) implements Light {
    public DirectionalLight {
        if (direction == null || color == null || !Float.isFinite(strength) || strength < 0) {
            throw new IllegalArgumentException(
                    "Directional light needs finite nonnegative strength");
        }
        double length =
                Math.sqrt(
                        (double) direction.x() * direction.x()
                                + (double) direction.y() * direction.y()
                                + (double) direction.z() * direction.z());
        if (!Double.isFinite(length) || length == 0) {
            throw new IllegalArgumentException("Direction must be finite and nonzero");
        }
        direction =
                new Vec3(
                        (float) (direction.x() / length),
                        (float) (direction.y() / length),
                        (float) (direction.z() / length));
        for (float channel : new float[] {color.x(), color.y(), color.z()}) {
            if (!Float.isFinite(channel) || channel < 0 || channel > 1) {
                throw new IllegalArgumentException("Light color must be 0..1 linear RGB");
            }
        }
    }

    /** A source at infinity cannot emit position-based rays in the legacy forward renderer. */
    @Override
    public List<Ray> sample(int n, RandomGenerator rng) {
        throw new UnsupportedOperationException("Directional lights require RGB path transport");
    }
}
