package rendering;

public interface Painter {
    void drawPoint(int x, int y, Color color, BlendMode blendMode);

    @FunctionalInterface
    interface LineSampler {
        Color apply(int i, int length, double progress);
    }

    void drawLine(int x1, int y1, int x2, int y2, LineSampler sampler, BlendMode blendMode);

    @FunctionalInterface
    interface ImageSampler {
        Color apply(int i, int x, int y);
    }

    void drawImg(int x, int y, int w, int h, ImageSampler sampler, BlendMode blendMode);

    default void drawImg(int x, int y, int w, int h, Color color, BlendMode blendMode) {
        drawImg(x, y, w, h, (_, _, _) -> color, blendMode);
    }

    default void drawImg(int x, int y, Raster raster, BlendMode blendMode) {
        var rasterReadable = raster.<Color>read();
        drawImg(x, y, raster.w(), raster.h(),
                (_, _, _) -> rasterReadable.next(Raster.Reader.READ_COLOR), blendMode);
    }

    void drawTri(int x1, int y1, int x2, int y2, int x3, int y3, Color color, BlendMode blendMode);
}
