package rendering;

public interface Raster {
    int width();

    int height();

    int[] rgb();

    byte[] alpha();

    byte[] red();

    byte[] green();

    byte[] blue();

    void setPixel(int x, int y, int argb);

    Raster scale(int width, int height);

    Raster clone();
}
