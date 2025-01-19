package rendering;

/**
 * Forward:  RGBA -> RGB
 * Backward: RGBA <- RGB
 */
public class RgbArgbConverter implements RasterConverter {
    @Override
    public Raster forward(Raster rgba) {
        int w = rgba.width();
        int h = rgba.height();
        var rgb = RasterFactory.create(w, h, 3);
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

    }
}
