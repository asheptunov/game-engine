package rendering;

public class PixelRaster implements ScalableRaster, CloneableRaster {
    private final int   width;
    private final int   height;
    private final int[] pixels;

    PixelRaster(Raster other) {
        this(other.width(), other.height(), other.pixels());
    }

    PixelRaster(int width, int height) {
        this(width, height, new int[width * height * 3]);
    }

    PixelRaster(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
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
//        boundsCheckPixel(x, y);
        setPixelFast(x, y, width, color);
    }

    // TODO add this back later but with a decorator
//    private void boundsCheckPixel(int x, int y) {
//        if (x < 0 || x > this.width) {
//            throw new IndexOutOfBoundsException(x);
//        }
//        if (y < 0 || y > this.height) {
//            throw new IndexOutOfBoundsException(y);
//        }
//    }

    private void setPixelFast(int x, int y, int width, int color) {
        int i = 3 * (y * width + x);
        pixels[i] = (color & 0xff0000) >> 16;
        pixels[i + 1] = (color & 0xff00) >> 8;
        pixels[i + 2] = color & 0xff;
    }

    @Override
    public PixelRaster scale(int toWidth, int toHeight) {
        if (toWidth < 1 || toHeight < 1) {
            throw new IllegalArgumentException();
        }
        double xFactor = 1. * toWidth / width;
        double yFactor = 1. * toHeight / height;
        var res = new PixelRaster(toWidth, toHeight);
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
    public PixelRaster clone() {
        var pixelsClone = new int[pixels.length];
        System.arraycopy(pixels, 0, pixelsClone, 0, pixels.length);
        return new PixelRaster(width, height, pixelsClone);
    }
}
