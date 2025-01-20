package rendering;

import java.util.HashMap;
import java.util.Map;

public class ConvertingLoader implements Font.Loader {
    private final RasterConverter converter;
    private final Font.Loader     delegate;

    public ConvertingLoader(RasterConverter converter, Font.Loader delegate) {
        this.converter = converter;
        this.delegate = delegate;
    }

    @Override
    public Font load() {
        return new ConvertedFont(delegate.load());
    }

    private class ConvertedFont implements Font {
        private final Font                   font;
        private final Raster                 nil;
        private final Map<Character, Raster> converted = new HashMap<>();

        private ConvertedFont(Font font) {
            this.font = font;
            this.nil = converter.forward(font.getNil());
        }

        @Override
        public Raster getNil() {
            return nil;
        }

        @Override
        public Raster getChar(char c) {
            return converted.computeIfAbsent(c, $ -> converter.forward(font.getChar(c)));
        }
    }
}
