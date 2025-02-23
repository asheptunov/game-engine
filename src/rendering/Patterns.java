package rendering;

public interface Patterns {
    static Raster checkerboard(int width, int height, float a1, float a2) {
        return new PixelRaster(width, height, (_, x, y)
                -> (x / 50) % 2 == 0
                ? (y / 50) % 2 == 0 ? Color.NamedColor.WHITE.withAlpha(a1) : Color.NamedColor.WHITE.withAlpha(a2)
                : (y / 50) % 2 == 0 ? Color.NamedColor.WHITE.withAlpha(a2) : Color.NamedColor.WHITE.withAlpha(a1));
    }
}
