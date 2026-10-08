package engine.lights;

import math.Ray;
import math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

public record PointLight(Vec3 position, Vec3 color, float intensity) implements Light {
    public PointLight(Vec3 position) {
        this(position, new Vec3(1, 1, 1), 1);
    }

    public PointLight {
        if (!Float.isFinite(intensity) || intensity < 0)
            throw new IllegalArgumentException("Intensity must be finite and >= 0");
        for (var v : new Vec3[] {position, color})
            if (!Float.isFinite(v.x()) || !Float.isFinite(v.y()) || !Float.isFinite(v.z()))
                throw new IllegalArgumentException("Light must be finite");
        if (color.x() < 0
                || color.y() < 0
                || color.z() < 0
                || color.x() > 1
                || color.y() > 1
                || color.z() > 1)
            throw new IllegalArgumentException("Light color must be 0..1 linear RGB");
    }

    @Override
    public List<Ray> sample(int n, RandomGenerator rng) {
        var rays = new ArrayList<Ray>(n);
        for (int i = 0; i < n; i++) {
            rays.add(new Ray(position, uniformUnitVector(rng)));
        }
        return rays;
    }

    private static Vec3 uniformUnitVector(RandomGenerator rng) {
        // z uniform in [-1, 1] and theta uniform in [0, 2π) gives uniform area on the unit sphere
        // because dA = sin(φ) dφ dθ and z = cos(φ) → dz = -sin(φ) dφ, so dA = dz dθ.
        float z = 2f * rng.nextFloat() - 1f;
        float theta = (float) (2 * Math.PI * rng.nextFloat());
        float r = (float) Math.sqrt(Math.max(0, 1 - z * z));
        return new Vec3(r * (float) Math.cos(theta), r * (float) Math.sin(theta), z);
    }
}
