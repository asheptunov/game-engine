package engine;

import java.util.Arrays;

/** Immutable opaque sRGB input, decoded once to linear reflectance. Rows run from top to bottom. */
public final class Texture2D {
    private final int width;
    private final int height;
    private final float[] linear;
    private final int hash;

    public Texture2D(int width, int height, int[] argb) {
        if (width < 1
                || width > 4096
                || height < 1
                || height > 4096
                || argb == null
                || argb.length != width * height) {
            throw new IllegalArgumentException(
                    "Texture needs 1..4096 dimensions and matching pixels");
        }
        this.width = width;
        this.height = height;
        linear = new float[argb.length * 3];
        for (int index = 0; index < argb.length; index++) {
            int pixel = argb[index];
            if ((pixel >>> 24) != 255) {
                throw new IllegalArgumentException("Texture pixels must be opaque");
            }
            linear[index * 3] = DisplayMapping.linear((pixel >>> 16) & 255);
            linear[index * 3 + 1] = DisplayMapping.linear((pixel >>> 8) & 255);
            linear[index * 3 + 2] = DisplayMapping.linear(pixel & 255);
        }
        hash = 31 * (31 * width + height) + Arrays.hashCode(linear);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Nearest texel, clamped at face edges. Caller owns the three-channel output scratch. */
    public void sample(float u, float v, float[] output) {
        int x = Math.clamp((int) Math.floor(u * width), 0, width - 1);
        int y = Math.clamp((int) Math.floor(v * height), 0, height - 1);
        int offset = (y * width + x) * 3;
        output[0] = linear[offset];
        output[1] = linear[offset + 1];
        output[2] = linear[offset + 2];
    }

    @Override
    public boolean equals(Object value) {
        return value == this
                || value instanceof Texture2D other
                        && width == other.width
                        && height == other.height
                        && Arrays.equals(linear, other.linear);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
