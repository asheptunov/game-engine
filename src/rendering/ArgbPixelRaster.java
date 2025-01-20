package rendering;

public class ArgbPixelRaster implements ScalableRaster, CloneableRaster {
    private final int           width;
    private final int           height;
    private final int[]         pixels;
    private final RasterFactory factory;

    ArgbPixelRaster(ArgbPixelRaster other) {
        this(other.width, other.height, other.pixels, other.factory);
    }

    ArgbPixelRaster(int width, int height, RasterFactory factory) {
        this(width, height, new int[width * height * 4], factory);
    }

    ArgbPixelRaster(int width, int height, int[] pixels, RasterFactory factory) {
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
        int i = (y * width + x) << 2;
        pixels[i++] = (byte) (color >> 24);  // red
        pixels[i++] = (byte) (color >> 16);  // green
        pixels[i++] = (byte) (color >> 8);  // blue
        pixels[i] = (byte) color;  // alpha
    }

    @Override
    public RasterFactory factory() {
        return factory;
    }

    @Override
    public ScalableRaster scale(int toWidth, int toHeight) {
        double xFactor = 1. * toWidth / width;
        double yFactor = 1. * toHeight / height;
        var res = new ArgbPixelRaster(toWidth, toHeight, factory);
        var to = res.pixels;
        int toI = 0;
        for (int y = 0; y < toHeight; ++y) {
            int fromY = (int) (y / yFactor);
            for (int x = 0; x < toWidth; ++x) {
                int fromX = (int) (x / xFactor);
                int fromI = (fromY * width + fromX) << 2;  // << 2 is fast * 4
                to[toI++] = pixels[fromI++];  // red
                to[toI++] = pixels[fromI++];  // green
                to[toI++] = pixels[fromI++];  // blue
                to[toI++] = pixels[fromI];  // alpha
            }
        }
        return res;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public CloneableRaster clone() {
        var pixelsClone = new int[pixels.length];
        System.arraycopy(pixels, 0, pixelsClone, 0, pixels.length);
        return new ArgbPixelRaster(width, height, pixelsClone, factory);
    }
}
