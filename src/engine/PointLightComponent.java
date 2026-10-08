package engine;

import math.Vec3;

import java.util.Objects;

/** Point light located at its node's world origin. */
public record PointLightComponent(Vec3 color, float intensity) {
    public PointLightComponent {
        Objects.requireNonNull(color, "color");
        if (!Float.isFinite(intensity) || intensity < 0)
            throw new IllegalArgumentException("Light intensity must be finite and nonnegative");
        for (float c : new float[] {color.x(), color.y(), color.z()})
            if (!Float.isFinite(c) || c < 0 || c > 1)
                throw new IllegalArgumentException("Light color must be finite linear RGB in 0..1");
    }
}
