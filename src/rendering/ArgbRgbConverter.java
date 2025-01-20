package rendering;

/**
 * Forward:  RGBA -> RGB
 * Backward: RGBA <- RGB
 */
public class ArgbRgbConverter implements RasterConverter {
    public static final ArgbRgbConverter INSTANCE = new ArgbRgbConverter();

    private static final RasterFactory RGB_FACTORY  = new RasterFactory(3);
    private static final RasterFactory ARGB_FACTORY = new RasterFactory(4);

    private ArgbRgbConverter() {}

    @Override
    public Raster forward(Raster rgba) {
        int w = rgba.width();
        int h = rgba.height();
        var rgb = RGB_FACTORY.create(w, h);
        var rgbPixels = rgb.pixels();
        var rgbaPixels = rgba.pixels();
        int rgbI = 0;
        int rgbaI = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                rgbPixels[rgbI++] = rgbaPixels[rgbaI++];  // red
                rgbPixels[rgbI++] = rgbaPixels[rgbaI++];  // green
                rgbPixels[rgbI++] = rgbaPixels[rgbaI++];  // blue
                ++rgbaI;  // skip alpha
            }
        }
        return rgb;
    }

    @Override
    public Raster backward(Raster rgb) {
        int w = rgb.width();
        int h = rgb.height();
        var rgba = ARGB_FACTORY.create(w, h);
        var rgbPixels = rgb.pixels();
        var rgbaPixels = rgba.pixels();
        int rgbI = 0;
        int rgbaI = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                rgbaPixels[rgbaI++] = rgbPixels[rgbI++];  // red
                rgbaPixels[rgbaI++] = rgbPixels[rgbI++];  // green
                rgbaPixels[rgbaI++] = rgbPixels[rgbI++];  // blue
                rgbaPixels[rgbaI++] = 0xff;  // alpha
            }
        }
        return rgba;
    }
}
