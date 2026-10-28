package engine;

/**
 * Read-only linear RGB access derived from a single-owner image lease. Use on the lease's owner
 * thread; reads and copies must finish before that enclosing lease is closed.
 */
public interface RgbPixels {
    int width();

    int height();

    float value(int channel, int x, int y);

    void copyTo(float[][][] destination);

    /** Copy packed 0x00RRGGBB pixels in top-down row order while the owning lease is open. */
    default void copyDisplayTo(int[] destination, float exposureMultiplier) {
        int w = width();
        int h = height();
        if (destination.length != w * h) {
            throw new IllegalArgumentException("Display destination size mismatch");
        }
        for (int y = 0; y < h; y++) {
            int row = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                destination[row + x] =
                        DisplayMapping.pack(
                                value(0, x, y), value(1, x, y), value(2, x, y), exposureMultiplier);
            }
        }
    }
}
