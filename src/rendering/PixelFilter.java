package rendering;

import static rendering.Raster.Reader.READ_COLOR;

public interface PixelFilter {
    Color apply(Color input);

    default RasterFilter asRasterFilter() {
        return raster -> {
            var inputReadable = raster.<Color>read();
            var res = raster.clone();
            res.write((_, _, _) -> apply(inputReadable.next(READ_COLOR)));
            return res;
        };
    }

    PixelFilter NO_OP = i -> i;

    static PixelFilter chromaKey(Color keyed) {
        return input -> keyed.rgbInt24() == input.rgbInt24() ? Color.NamedColor.NONE : input;
    }

    static PixelFilter chromaMap(Color from, Color to) {
        return input -> from.rgbInt24() == input.rgbInt24() ? to.withAlpha(input.alpha()) : input;
    }

    static PixelFilter opacity(double opacity) {
        return input -> input.withAlpha((byte) (opacity * ((int) input.alpha() & 0xff)));
    }
}
