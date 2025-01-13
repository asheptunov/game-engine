package rendering;

import java.util.function.BiFunction;

public interface Painter {
    void drawPoint(int x, int y, int color);

    void drawLine(int x1, int y1, int x2, int y2, int color);

    void drawLine(int x1, int y1, int x2, int y2, BiFunction<Integer, Double, Integer> pattern);

    void drawImg(int x, int y, Raster imgRaster);

    void drawTri(int x1, int y1, int x2, int y2, int x3, int y3, int edgeColor, int fillColor);
}
