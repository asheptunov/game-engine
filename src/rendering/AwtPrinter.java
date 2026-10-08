package rendering;

import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Native monospaced text, positioned by cell top-left. No filesystem font assets required. Size
 * selects the AWT point size; Spacing adds pixels to the measured character advance. Like
 * RasterPrinter, instances belong to their rendering thread.
 */
public final class AwtPrinter implements Printer {
    private record GlyphKey(char character, int size) {}

    private final Painter painter;
    private final int size;
    private final Map<Integer, FontMetrics> metrics = new HashMap<>();
    private final Map<GlyphKey, Raster> glyphs = new HashMap<>();

    public AwtPrinter(Raster raster, int size) {
        this.painter = new RasterPainter(raster);
        this.size = size;
        metrics(size);
    }

    public int cellWidth() {
        return metrics(size).charWidth('M');
    }

    public int cellHeight() {
        return metrics(size).getHeight();
    }

    private FontMetrics metrics(int size) {
        return metrics.computeIfAbsent(
                size,
                s -> {
                    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                    var g = image.createGraphics();
                    try {
                        return g.getFontMetrics(AwtText.monospaced(s));
                    } finally {
                        g.dispose();
                    }
                });
    }

    private Raster glyph(GlyphKey key) {
        var fm = metrics(key.size());
        var image =
                new BufferedImage(fm.charWidth('M'), fm.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setFont(AwtText.monospaced(key.size()));
            g.setColor(java.awt.Color.WHITE);
            g.drawString(String.valueOf(key.character()), 0, fm.getAscent());
        } finally {
            g.dispose();
        }
        return new PixelRaster(
                image.getWidth(),
                image.getHeight(),
                (_, x, y) -> rendering.Color.ArgbInt32Color.of(image.getRGB(x, y)));
    }

    @Override
    public void print(char c, int x, int y, Style... styles) {
        int pointSize = size;
        rendering.Color color = rendering.Color.NamedColor.WHITE;
        rendering.BlendMode blend = rendering.BlendMode.OVER_PRE;
        for (var style : styles) {
            switch (style) {
                case Size s -> pointSize = s.size();
                case Color cl -> color = cl.color();
                case BlendMode b -> blend = b.blendMode();
                case Spacing _ -> {}
            }
        }
        var mask = glyphs.computeIfAbsent(new GlyphKey(c, pointSize), this::glyph);
        final var ink = color;
        final boolean premultiply = blend == rendering.BlendMode.OVER_PRE;
        painter.drawImg(
                x,
                y,
                mask.width(),
                mask.height(),
                (i, _, _) -> {
                    int alpha = (mask.alpha()[i] & 255) * (ink.a() & 255) / 255;
                    int factor = premultiply ? alpha : 255;
                    return rendering.Color.ArgbInt32Color.of(
                            (byte) alpha,
                            (byte) ((ink.r() & 255) * factor / 255),
                            (byte) ((ink.g() & 255) * factor / 255),
                            (byte) ((ink.b() & 255) * factor / 255));
                },
                blend);
    }

    @Override
    public void print(String text, int x, int y, Style... styles) {
        int pointSize = size, spacing = 0;
        for (var style : styles) {
            if (style instanceof Size s) pointSize = s.size();
            if (style instanceof Spacing s) spacing = s.spacing();
        }
        int advance = metrics(pointSize).charWidth('M') + spacing;
        for (char c : text.toCharArray()) {
            print(c, x, y, styles);
            x += advance;
        }
    }
}
