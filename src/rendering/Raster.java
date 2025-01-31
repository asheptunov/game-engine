package rendering;

public interface Raster {
    int width();

    int height();

    int[] rgb();

    byte[] alpha();

    byte[] red();

    byte[] green();

    byte[] blue();

    Color pixel(int x, int y);

    void pixel(int x, int y, Color color);

    Raster scale(int width, int height);

    Raster clone();
}
