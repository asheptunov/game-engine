package rendering;

import java.util.function.BiFunction;

public interface Painter {
    void drawPoint(int x, int y, Color color);

    void drawLine(int x1, int y1, int x2, int y2, Color color);

    void drawLine(int x1, int y1, int x2, int y2, BiFunction<Integer, Double, Color> pattern);

    void drawImg(int x, int y, Raster imgRaster);

    void drawTri(int x1, int y1, int x2, int y2, int x3, int y3, Color edgeColor, Color fillColor);
}
