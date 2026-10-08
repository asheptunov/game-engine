package rendering;

/** Packs the composed raster into reusable AWT storage, preserving alpha-over-black behavior. */
public final class RgbPacking {
    private RgbPacking() {}

    public static void copy(Raster raster, int[] pixels) {
        if (pixels.length != raster.width() * raster.height())
            throw new IllegalArgumentException("Pixel count mismatch");
        var a = raster.alpha();
        var r = raster.red();
        var g = raster.green();
        var b = raster.blue();
        for (int p = 0; p < pixels.length; p++) {
            if (a[p] == (byte) 255) {
                pixels[p] = ((r[p] & 255) << 16) | ((g[p] & 255) << 8) | (b[p] & 255);
            } else {
                double alpha = (a[p] & 255) / 255.;
                pixels[p] =
                        ((int) (alpha * (r[p] & 255)) << 16)
                                | ((int) (alpha * (g[p] & 255)) << 8)
                                | (int) (alpha * (b[p] & 255));
            }
        }
    }
}
