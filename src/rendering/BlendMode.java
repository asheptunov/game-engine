package rendering;

import static rendering.Raster.Reader.READ_COLOR;

public interface BlendMode {
    Color apply(Color fg, Color bg);

    default Raster apply(Raster fg, Raster bg) {
        var fgReadable = fg.<Color>read();
        var bgReadable = bg.<Color>read();
        var res = bg.clone();
        res.write((_, _, _) -> apply(fgReadable.next(READ_COLOR), bgReadable.next(READ_COLOR)));
        return res;
    }

    BlendMode NORMAL = (fg, _) -> fg;

    BlendMode OVER_STRAIGHT = (fg, bg) -> {
        var fgA = bToD(fg.a());
        var bgA = bToD(bg.a());
        var oneMinusFgA = 1 - fgA;
        var a = dToB(fgA + bgA * oneMinusFgA);
        var r = dToB((bToD(fg.r()) * fgA + bToD(bg.r()) * bgA * oneMinusFgA) / fgA);
        var g = dToB((bToD(fg.g()) * fgA + bToD(bg.g()) * bgA * oneMinusFgA) / fgA);
        var b = dToB((bToD(fg.b()) * fgA + bToD(bg.b()) * bgA * oneMinusFgA) / fgA);
        return Color.ArgbInt32Color.of(a, r, g, b);
    };

    BlendMode OVER_PRE = (fg, bg) -> {
        var fgA = bToD(fg.a());
        var oneMinusFgA = 1 - fgA;
        var a = dToB(fgA + bToD(bg.a()) * oneMinusFgA);
        var r = dToB(bToD(fg.r()) + bToD(bg.r()) * oneMinusFgA);
        var g = dToB(bToD(fg.g()) + bToD(bg.g()) * oneMinusFgA);
        var b = dToB(bToD(fg.b()) + bToD(bg.b()) * oneMinusFgA);
        return Color.ArgbInt32Color.of(a, r, g, b);
    };

    BlendMode SUBTRACT = (fg, bg) -> {
        var fgA = bToD(fg.a());
        var bgR = bToD(bg.r());
        var bgG = bToD(bg.g());
        var bgB = bToD(bg.b());
        var fgR = bToD(fg.r());
        var fgG = bToD(fg.g());
        var fgB = bToD(fg.b());
        var oneMinusFgA = 1 - fgA;
        var r = dToB(fgA * clipD(fgR - bgR) + oneMinusFgA * bgR);
        var g = dToB(fgA * clipD(fgG - bgG) + oneMinusFgA * bgG);
        var b = dToB(fgA * clipD(fgB - bgB) + oneMinusFgA * bgB);
        return Color.ArgbInt32Color.of(bg.a(), r, g, b);
    };

    double ONE_OVER_255 = 1 / 255.;

    private static double bToD(byte b) {
        return ((int) b & 0xff) * ONE_OVER_255;
    }

    private static byte dToB(double d) {
        return (byte) (255. * clipD(d));
    }

    private static double clipD(double d) {
        return Math.max(0, Math.min(1, d));
    }
}
