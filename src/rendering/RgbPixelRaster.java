package rendering;

public class RgbPixelRaster implements ScalableRaster, CloneableRaster {
    private final int           width;
    private final int           height;
    private final int[]         pixels;
    private final RasterFactory factory;

    RgbPixelRaster(RgbPixelRaster other) {
        this(other.width, other.height, other.pixels, other.factory);
    }

    RgbPixelRaster(int width, int height, RasterFactory factory) {
        this(width, height, new int[width * height * 3], factory);
    }

    RgbPixelRaster(int width, int height, int[] pixels, RasterFactory factory) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.factory = factory;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int[] pixels() {
        return pixels;
    }

    @Override
    public void setPixel(int x, int y, int color) {
        int i = 3 * (y * width + x);
        pixels[i] = (color & 0xff0000) >> 16;
        pixels[i + 1] = (color & 0xff00) >> 8;
        pixels[i + 2] = color & 0xff;
    }

    @Override
    public RasterFactory factory() {
        return factory;
    }

    @Override
    public RgbPixelRaster scale(int toWidth, int toHeight) {
        double xFactor = 1. * toWidth / width;
        double yFactor = 1. * toHeight / height;
        var res = new RgbPixelRaster(toWidth, toHeight, factory);
        var to = res.pixels;
        // TODO there are faster but less accurate ways of doing this (get rid of the mul / div)
        // TODO this is linear sampling. can also use alternatives (e.g. averaging) to get smoother results.
        for (int toR = 0; toR < toHeight; ++toR) {
            for (int toC = 0; toC < toWidth; ++toC) {
                // "from" indexes into raster being scaled ("this")
                int fromR = (int) (toR / yFactor);
                int fromC = (int) (toC / xFactor);
                int fromI = 3 * (fromR * width + fromC);
                // "to" indexes into target raster
                int toI = 3 * (toR * toWidth + toC);
                to[toI++] = pixels[fromI++];
                to[toI++] = pixels[fromI++];
                to[toI] = pixels[fromI];
            }
        }
        return res;
    }

    @Override
    public RgbPixelRaster clone() {
        var pixelsClone = new int[pixels.length];
        System.arraycopy(pixels, 0, pixelsClone, 0, pixels.length);
        return new RgbPixelRaster(width, height, pixelsClone, factory);
    }
}
