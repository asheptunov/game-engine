package rendering;

public class RasterPrinter implements Printer {
    private final Painter painter;
    // TODO bezier fonts
    private final Font    font;
    private final int     spacing;

    public RasterPrinter(Raster raster, Font font, int spacing) {
        this.painter = switch (raster) {
            case RgbPixelRaster rgb -> new RgbRasterPainter(rgb);
            case ArgbPixelRaster argb -> new ArgbRasterPainter(argb);
            default -> throw new IllegalArgumentException(raster.getClass().getName());
        };
        this.font = font;
        this.spacing = spacing;
    }

    @Override
    public void print(char c, int x, int y, int size) {
        var asset = font.getChar(c);
        var scaled = asset.factory().scalable(asset).scale(size, size);
        painter.drawImg(x, y, scaled);
    }

    @Override
    public void print(String str, int x, int y, int size) {
        if (str.isEmpty()) {
            return;
        }
        for (char c : str.toCharArray()) {
            print(Character.toLowerCase(c), x, y, size);
            x += size + spacing;
        }
    }
}
