package rendering;

public interface Raster {
    int width();

    int height();

    // should this return a native AWT INT_RGB raster? makes everything simpler but annoying if you need to convert
    int[] pixels();

    void setPixel(int x, int y, int color);

    RasterFactory factory();
}
