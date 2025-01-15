package rendering;

import logging.LogManager;
import logging.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RasterPrinter implements Printer {
    private static final Logger                 LOG         = LogManager.instance().getThis();
    private static final Map<Character, String> FONT_FS_MAP = new HashMap<>();

    static {
        for (char c = 'a'; c <= 'z'; ++c) {
            FONT_FS_MAP.put(c, Character.toString(c));
        }
        for (char c = '0'; c <= '9'; ++c) {
            FONT_FS_MAP.put(c, Character.toString(c));
        }
        FONT_FS_MAP.put('\0', "nil");
        FONT_FS_MAP.put('.', "dot");
        FONT_FS_MAP.put('/', "slash");
        FONT_FS_MAP.put(' ', "space");
        FONT_FS_MAP.put('-', "hyphen");
        FONT_FS_MAP.put('_', "underscore");
        FONT_FS_MAP.forEach((c, filename) -> FONT_FS_MAP.put(c, filename + ".tx"));
    }

    private final Painter                painter;
    // TODO bezier fonts
    private final Map<Character, Raster> font;
    private final Raster                 nil;
    private final int                    spacing;

    private RasterPrinter(Raster raster, Map<Character, Raster> font, Raster nil, int spacing) {
        this.painter = new RasterPainter(raster);
        this.font = font;
        this.nil = nil;
        this.spacing = spacing;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Raster           raster;
        private RasterRepository repository;
        private Clock            clock;
        private Path             fontPath;
        private Integer          baseWidth;
        private Integer          baseHeight;
        private int              spacing = 0;

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

        public Builder fontDimensions(int baseWidth, int baseHeight) {
            if (baseWidth < 1) {
                throw new IllegalArgumentException("Font base width must be a positive integer");
            }
            if (baseHeight < 1) {
                throw new IllegalArgumentException("Font base height must be a positive integer");
            }
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            return this;
        }

        public Builder spacing(int spacing) {
            this.spacing = spacing;
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
            if (baseWidth == null || baseHeight == null) {
                throw new IllegalArgumentException("dimensions");
            }
            var font = loadFont();
            return new RasterPrinter(raster, font, font.get('\0'), spacing);
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
                var loadResult = repository.load(assetPath);
                loadResult
                        .ifFailure(ex -> LOG.warn(ex, "Failed to read asset file for char '%c': %s", c, filename))
                        .mapFailure(Exception::getMessage)
                        .filter(asset -> asset.width() == baseWidth,
                                asset -> "Font has incorrect width %d for char '%c' (baseWidth=%d)".formatted(
                                        asset.width(), c, baseWidth))
                        .filter(asset -> asset.height() == baseHeight,
                                asset -> "Font has incorrect height %d for char '%c' (baseHeight=%d)".formatted(
                                        asset.height(), c, baseHeight))
                        .ifFailure(LOG::warn)
                        .ifSuccess(asset -> {
                            LOG.debug("Loaded asset for '%c' from %s", c, filename);
                            res.put(c, asset);
                        });
            });
            // nil must be loadable (to render missing textures), but can be overridden
            res.putIfAbsent('\0', RasterFactory.create(baseWidth, baseHeight));
            var end = clock.instant();
            LOG.info("Loaded font %s in %s", fontPath, Duration.between(start, end));
            return res;
        }
    }

    @Override
    public void print(char c, int x, int y, int size) {
        var asset = Optional.ofNullable(font.get(c)).orElse(nil);
        var scaled = RasterFactory.scalable(asset).scale(size, size);
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
