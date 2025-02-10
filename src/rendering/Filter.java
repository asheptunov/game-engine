package rendering;

import static rendering.Raster.Reader.READ_COLOR;

public interface Filter {
    Color apply(Color input);

    default Raster apply(Raster input) {
        var inputReadable = input.<Color>read();
        var res = input.clone();
        res.write((_, _, _) -> apply(inputReadable.next(READ_COLOR)));
        return res;
    }

    Filter NO_OP = i -> i;

    static Filter chromaKey(Color keyed) {
        return input -> keyed.rgbInt24() == input.rgbInt24() ? Color.NamedColor.NONE : input;
    }

    static Filter chromaMap(Color from, Color to) {
        return input -> from.rgbInt24() == input.rgbInt24() ? to : input;
    }

    static Filter opacity(double opacity) {
        return input -> input.withAlpha((byte) (opacity * ((int) input.alpha() & 0xff)));
    }
}
