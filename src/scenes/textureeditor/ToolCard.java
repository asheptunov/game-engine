package scenes.textureeditor;

import rendering.BlendMode;
import rendering.Color;
import rendering.Filter;
import rendering.Painter;
import rendering.PixelRaster;
import rendering.Printer;
import rendering.Raster;
import rendering.RasterPainter;
import rendering.RasterPrinter;
import rendering.Renderer;
import ui.KeyAction.Key;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ToolCard implements Renderer {
    private final Painter                           displayPainter;
    private final int                               fontSize;
    private final int                               charSpacing;
    private final int                               cols;
    private final int                               rows;
    private final int                               stride;
    private final int                               width;
    private final int                               height;
    private final Raster                            card;
    private final Painter                           cardPainter;
    private final Printer                           cardPrinter;
    private final int                               x;
    private final int                               y;
    private final int                               gridSize = 50;
    private final int                               padding  = 4;
    private final Map<Integer, Map<Integer, Entry>> grid     = Map.of(
            0, Map.of(
                    0, Entry.of(Key.LOWER_Q).build(),
                    1, Entry.of(Key.LOWER_W).build(),
                    2, Entry.of(Key.LOWER_E).build(),
                    3, Entry.of(Key.LOWER_R).build(),
                    4, Entry.of(Key.LOWER_T).build()
            ),
            1, Map.of(
                    0, Entry.of(Key.LOWER_A).build(),
                    1, Entry.of(Key.LOWER_S).build(),
                    2, Entry.of(Key.LOWER_D).build(),
                    3, Entry.of(Key.LOWER_F).build(),
                    4, Entry.of(Key.LOWER_G).build()
            ),
            2, Map.of(
                    0, Entry.of(Key.LOWER_Z).build(),
                    1, Entry.of(Key.LOWER_X).build(),
                    2, Entry.of(Key.LOWER_C).build(),
                    3, Entry.of(Key.LOWER_V).build(),
                    4, Entry.of(Key.LOWER_B).build()
            ),
            3, Map.of(
                    0, Entry.of(Key.L_CTRL).withFriendly("Ctrl").build(),
                    1, Entry.of(Key.L_ALT).withFriendly("Alt").build(),
                    2, Entry.empty(),
                    3, Entry.empty(),
                    4, Entry.empty()
            )
    );

    private record Entry(Key key, String friendlyKey, String action) {
        static Builder of(Key key) {
            if (key == null) {
                throw new IllegalArgumentException();
            }
            return new Builder(key);
        }

        static Entry empty() {
            return new Entry(null, null, null);
        }

        public static class Builder {
            private final Key    key;
            private       String friendlyKey;
            private       String action;

            private Builder(Key key) {
                this.key = key;
                this.friendlyKey = key.character().map(String::valueOf).orElseGet(key::name);
            }

            Builder withFriendly(String friendlyKey) {
                this.friendlyKey = friendlyKey;
                return this;
            }

            Builder withAction(String action) {
                this.action = action;
                return this;
            }

            Entry build() {
                return new Entry(key, friendlyKey, action);
            }
        }
    }

    public ToolCard(TextureEditor editor) {
        this.displayPainter = editor.painter();
        this.fontSize = editor.fontSize();
        this.charSpacing = editor.charSpacing();
        this.cols = grid.get(0).size();
        this.rows = grid.size();
        this.stride = gridSize + padding;
        this.width = cols * stride + padding;
        this.height = rows * stride + padding;
        this.card = new PixelRaster(width, height);
        this.cardPainter = new RasterPainter(card);
        this.cardPrinter = new RasterPrinter(card, editor.font());
        this.x = 0;
        this.y = editor.display().height() - height;
    }

    @Override
    public void render() {
        renderPadding();
        renderGrid();
        renderKeys();
        displayPainter.drawImg(x, y, Filter.opacity(0.9).apply(card), BlendMode.OVER_PRE);
    }

    private void renderPadding() {
        cardPainter.drawImg(0, 0, width, height, Color.RgbInt24Color.of(0x4989c4), BlendMode.OVER_PRE);
    }

    private void renderGrid() {
        var xStart = new AtomicInteger(padding);
        for (int c = 0; c < cols; ++c) {
            var yStart = new AtomicInteger(padding);
            for (int r = 0; r < rows; ++r) {
                cardPainter.drawImg(xStart.get(), yStart.get(), gridSize, gridSize,
                        Color.NamedColor.WHITE, BlendMode.OVER_PRE);
                yStart.addAndGet(stride);
            }
            xStart.addAndGet(stride);
        }
    }

    private void renderKeys() {
        var xStart = new AtomicInteger(padding);
        for (int c = 0; c < cols; ++c) {
            var yStart = new AtomicInteger(padding);
            for (int r = 0; r < rows; ++r) {
                var entry = grid.get(r).get(c);
                if (entry == null) {
                    continue;
                }
                var key = entry.friendlyKey();
                if (key == null) {
                    continue;
                }
                var scale = Math.min(1, 1. * (gridSize - padding - padding)
                        / (key.length() * (this.fontSize + this.charSpacing)));
                var fontSize = (int) (scale * this.fontSize);
                var charSpacing = (int) (scale * this.charSpacing);
                cardPrinter.print(key, xStart.get() + padding, yStart.get() + padding,
                        Printer.Size.of(fontSize),
                        Printer.Spacing.of(charSpacing),
                        Printer.BlendMode.of(BlendMode.SUBTRACT));
                yStart.addAndGet(stride);
            }
            xStart.addAndGet(stride);
        }
    }
}
