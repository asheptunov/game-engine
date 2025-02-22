package rendering;

import static rendering.Color.NamedColor;

public class RasterPrinter implements Printer {
    private final Painter painter;
    // TODO bezier fonts
    private final Font    font;

    public RasterPrinter(Raster raster, Font font) {
        this.painter = new RasterPainter(raster);
        this.font = font;
    }

    @Override
    public void print(char c, int x, int y, Style... styles) {
        var blendMode = rendering.BlendMode.OVER_PRE;
        var color = (rendering.Color) NamedColor.WHITE;
        int size = font.size();
        for (Style style : styles) {
            switch (style) {
                case BlendMode bm -> blendMode = bm.blendMode();
                case Color cl -> color = cl.color();
                case Size sz -> size = sz.size();
                case Spacing _ -> {}
            }
        }
        var asset = font.getChar(Character.toLowerCase(c));  // todo add uppercase
        asset = PixelFilter.chromaMap(NamedColor.BLACK, color).asRasterFilter().apply(asset);
        var scaled = asset.scale(size, size);
        painter.drawImg(x, y, scaled, blendMode);
    }

    @Override
    public void print(String str, int x, int y, Style... styles) {
        if (str.isEmpty()) {
            return;
        }
        int size = font.size();
        int spacing = 0;
        for (Style style : styles) {
            switch (style) {
                case BlendMode _, Color _ -> {}
                case Size sz -> size = sz.size();
                case Spacing sp -> spacing = sp.spacing();
            }
        }
        for (char c : str.toCharArray()) {
            print(Character.toLowerCase(c), x, y, styles);
            x += size + spacing;
        }
    }
}
