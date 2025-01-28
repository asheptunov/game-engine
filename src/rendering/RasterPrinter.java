package rendering;

import logging.LogManager;
import logging.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static rendering.Color.NamedColor;

public class RasterPrinter implements Printer {
    private static final Logger                 LOG         = LogManager.instance().getThis();
    private static final Map<Character, String> FONT_FS_MAP = new HashMap<>() {{
        for (char c = 'a'; c <= 'z'; ++c) {
            put(c, Character.toString(c));
        }
        for (char c = '0'; c <= '9'; ++c) {
            put(c, Character.toString(c));
        }
        put('\0', "nil");
        put(' ', "space");
        put('!', "bang");
        put('"', "quotation");
        put('#', "hash");
        put('$', "dollar");
        put('%', "percent");
        put('&', "ampersand");
        put('\'', "apostrophe");
        put('(', "left_paren");
        put(')', "right_paren");
        put('*', "star");
        put('+', "plus");
        put(',', "comma");
        put('-', "hyphen");
        put('.', "dot");
        put('/', "slash");
        put(':', "colon");
        put(';', "semicolon");
        put('<', "less_than");
        put('=', "equal");
        put('>', "greater_than");
        put('?', "question");
        put('@', "at");
        put('[', "left_square_bracket");
        put('\\', "backslash");
        put(']', "right_square_bracket");
        put('^', "caret");
        put('_', "underscore");
        put('`', "grave");
        put('{', "left_curly_brace");
        put('|', "pipe");
        put('}', "right_curly_brace");
        put('~', "tilde");
        forEach((c, filename) -> put(c, filename + ".tx"));
    }};

    private final Painter painter;
    // TODO bezier fonts
    private final Font    font;

    private RasterPrinter(Raster raster, Font font) {
        this.painter = new RasterPainter(raster);
        this.font = font;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Raster           raster;
        private RasterRepository repository;
        private Clock            clock;
        private Path             fontPath;
        private Integer          size;
        private RasterFilter     filter = RasterFilter.NO_OP;

        private Builder() {}

        public Builder raster(Raster raster) {
            this.raster = raster;
            return this;
        }

        public Builder repository(RasterRepository repository) {
            this.repository = repository;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder fontPath(String fontPath) {
            this.fontPath = Path.of(fontPath);
            return this;
        }

        public Builder fontDimensions(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("Font size must be a positive integer");
            }
            this.size = size;
            return this;
        }

        public Builder filter(RasterFilter filter) {
            this.filter = filter;
            return this;
        }

        public RasterPrinter build() {
            if (raster == null) {
                throw new IllegalArgumentException("raster");
            }
            if (repository == null) {
                throw new IllegalArgumentException("repository");
            }
            if (clock == null) {
                throw new IllegalArgumentException("clock");
            }
            if (fontPath == null) {
                throw new IllegalArgumentException("fontPath");
            }
            if (size == null) {
                throw new IllegalArgumentException("size");
            }
            var font = loadFont();
            var nil = font.get('\0');
            return new RasterPrinter(raster, new InMemoryFont(font, nil.width()));
        }

        private Map<Character, Raster> loadFont() {
            var res = new HashMap<Character, Raster>();
            var start = clock.instant();
            var fontDir = fontPath.toFile();
            if (!fontDir.exists()) {
                throw new IllegalArgumentException("Font path doesn't exist: " + fontPath);
            }
            if (!fontDir.isDirectory()) {
                throw new IllegalArgumentException("Font path is not a directory: " + fontPath);
            }
            FONT_FS_MAP.forEach((c, filename) -> {
                var assetPath = fontPath.resolve("standard").resolve(filename);
                var loadResult = repository.load(assetPath.toFile());
                loadResult
                        .ifFailure(ex -> LOG.warn(ex, "Failed to read asset file for char '%c': %s", c, filename))
                        .mapFailure(Exception::getMessage)
                        .filter(asset -> asset.width() == size,
                                asset -> "Font has incorrect width %d for char '%c' (baseWidth=%d)".formatted(
                                        asset.width(), c, size))
                        .filter(asset -> asset.height() == size,
                                asset -> "Font has incorrect height %d for char '%c' (baseHeight=%d)".formatted(
                                        asset.height(), c, size))
                        .ifFailure(LOG::warn)
                        .ifSuccess(asset -> {
                            LOG.debug("Loaded asset for '%c' from %s", c, filename);
                            res.put(c, asset);
                        });
            });
            // nil must be loadable (to render missing textures), but can be overridden
            res.putIfAbsent('\0', new PixelRaster(size, size, (_, _) -> NamedColor.BLACK));
            res.replaceAll((_, v) -> filter.apply(v));
            LOG.info("Loaded font %s in %s", fontPath, Duration.between(start, clock.instant()));
            return res;
        }
    }

    @Override
    public void print(char c, int x, int y, Style... styles) {
        var color = (rendering.Color) NamedColor.WHITE;
        int size = font.size();
        for (Style style : styles) {
            switch (style) {
                case Color cl -> color = cl.color();
                case Size sz -> size = sz.size();
                case Spacing _ -> {}
            }
        }
        var asset = font.getChar(Character.toLowerCase(c));  // todo add uppercase
        if (!NamedColor.WHITE.equals(color)) {
            asset = ColorMap.of(NamedColor.WHITE, color).apply(asset);
        }
        var scaled = asset.scale(size, size);
        painter.drawImg(x, y, scaled);
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
                case Size sz -> size = sz.size();
                case Spacing sp -> spacing = sp.spacing();
                case Color _ -> {}
            }
        }
        for (char c : str.toCharArray()) {
            print(Character.toLowerCase(c), x, y, styles);
            x += size + spacing;
        }
    }
}
