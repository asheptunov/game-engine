package scenes.viewport;

import math.Vec3;

/** Linear RGB diffuse reflectance. UI colors are decoded from sRGB before storage. */
public record Material(String name, Vec3 color) {
    public Material {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Material needs a name");
        for (float c : new float[]{color.x(), color.y(), color.z()})
            if (!Float.isFinite(c) || c < 0 || c > 1) throw new IllegalArgumentException("Reflectance must be 0..1");
    }
    public static Material srgb(String name, int rgb) {
        return new Material(name, new Vec3(DisplayMapping.linear((rgb >> 16) & 255),
                DisplayMapping.linear((rgb >> 8) & 255), DisplayMapping.linear(rgb & 255)));
    }
}
