package rendering;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import static rendering.Color.AnsiColor;
import static rendering.Color.ArgbInt32Color;
import static rendering.Color.NamedColor;
import static rendering.Color.RgbInt24Color;

public sealed interface Color permits AnsiColor, ArgbInt32Color, NamedColor, RgbInt24Color {
    int rgbInt24();

    int argbInt32();

    byte alpha();

    Color withAlpha(byte alpha);

    default Color withAlpha(float alpha) {
        return withAlpha((byte) (Math.max(0, Math.min(1, alpha)) * 255));
    }

    byte red();

    Color withRed(byte red);

    default Color withRed(float red) {
        return withRed((byte) (Math.max(0, Math.min(1, red)) * 255));
    }

    byte green();

    Color withGreen(byte green);

    default Color withGreen(float green) {
        return withGreen((byte) (Math.max(0, Math.min(1, green)) * 255));
    }

    byte blue();

    Color withBlue(byte blue);

    default Color withBlue(float blue) {
        return withBlue((byte) (Math.max(0, Math.min(1, blue)) * 255));
    }

    final class AnsiColor implements Color {
        private static final Map<Integer, AnsiColor> CODE_INDEX = Collections.synchronizedMap(new HashMap<>());

        public static final AnsiColor NONE    = new AnsiColor(0, NamedColor.WHITE.withAlpha(0));
        public static final AnsiColor BLACK   = new AnsiColor(30, NamedColor.BLACK);
        public static final AnsiColor RED     = new AnsiColor(31, NamedColor.RED);
        public static final AnsiColor GREEN   = new AnsiColor(32, NamedColor.GREEN);
        public static final AnsiColor YELLOW  = new AnsiColor(33, NamedColor.YELLOW);
        public static final AnsiColor BLUE    = new AnsiColor(34, NamedColor.BLUE);
        public static final AnsiColor MAGENTA = new AnsiColor(35, NamedColor.MAGENTA);
        public static final AnsiColor CYAN    = new AnsiColor(36, NamedColor.CYAN);
        public static final AnsiColor WHITE   = new AnsiColor(37, NamedColor.WHITE);

        private final Color color;
        private final int   code;

        private AnsiColor(int code, Color color) {
            this.code = code;
            this.color = color;
            CODE_INDEX.computeIfPresent(code, (k, v) -> {
                throw new IllegalArgumentException("There is already a color with ANSI code '" + k + "': " + v);
            });
            CODE_INDEX.put(code, this);
        }

        public static Optional<AnsiColor> of(int code) {
            return Optional.ofNullable(CODE_INDEX.get(code));
        }

        public static String formatted(Color color) {
            if (color instanceof AnsiColor ansi) {
                return ansi.formatted();
            }
            return "\\033[38;2;%d;%d;%dm".formatted(
                    (int) color.red() & 0xff,
                    (int) color.green() & 0xff,
                    (int) color.blue() & 0xff);
        }

        public static String formatted(AnsiColor... colors) {
            return "\\033["
                    + Arrays.stream(colors).map(AnsiColor::code).map(String::valueOf)
                    .collect(Collectors.joining(";"))
                    + "m";
        }

        public String formatted() {
            return "\\033[" + code + "m";
        }

        public int code() {
            return code;
        }

        @Override
        public int rgbInt24() {
            return color.rgbInt24();
        }

        @Override
        public int argbInt32() {
            return color.argbInt32();
        }

        @Override
        public byte alpha() {
            return color.alpha();
        }

        @Override
        public Color withAlpha(byte alpha) {
            return color.withAlpha(alpha);
        }

        @Override
        public byte red() {
            return color.red();
        }

        @Override
        public Color withRed(byte red) {
            return color.withRed(red);
        }

        @Override
        public byte green() {
            return color.green();
        }

        @Override
        public Color withGreen(byte green) {
            return color.withGreen(green);
        }

        @Override
        public byte blue() {
            return color.blue();
        }

        @Override
        public Color withBlue(byte blue) {
            return color.withBlue(blue);
        }
    }

    final class NamedColor implements Color {
        private static final Map<String, NamedColor> NAME_INDEX = Collections.synchronizedMap(new HashMap<>());
        private static final Random                  RANDOM     = new Random();

        public static final NamedColor BLACK   = new NamedColor("black", new RgbInt24Color(0x0));
        public static final NamedColor WHITE   = new NamedColor("white", new RgbInt24Color(0xffffff));
        public static final NamedColor RED     = new NamedColor("red", new RgbInt24Color(0xff0000));
        public static final NamedColor YELLOW  = new NamedColor("yellow", new RgbInt24Color(0xffff00));
        public static final NamedColor GREEN   = new NamedColor("green", new RgbInt24Color(0xff00));
        public static final NamedColor CYAN    = new NamedColor("cyan", new RgbInt24Color(0x00ffff));
        public static final NamedColor BLUE    = new NamedColor("blue", new RgbInt24Color(0xff));
        public static final NamedColor MAGENTA = new NamedColor("magenta", new RgbInt24Color(0xff00ff));

        private final String name;
        private final Color  color;

        private NamedColor(String name, Color color) {
            this.name = name;
            this.color = color;
            NAME_INDEX.computeIfPresent(name, (k, v) -> {
                throw new IllegalArgumentException("There is already a color named '" + k + "': " + v);
            });
            NAME_INDEX.put(name, this);
        }

        public static Collection<String> names() {
            return NAME_INDEX.keySet();
        }

        public static Optional<NamedColor> of(String name) {
            return Optional.ofNullable(NAME_INDEX.get(name));
        }

        public static NamedColor random() {
            return of(names().stream().skip(RANDOM.nextInt(names().size())).findFirst().orElseThrow()).orElseThrow();
        }

        public String name() {
            return name;
        }

        @Override
        public int rgbInt24() {
            return color.rgbInt24();
        }

        @Override
        public int argbInt32() {
            return color.argbInt32();
        }

        @Override
        public byte alpha() {
            return color.alpha();
        }

        @Override
        public Color withAlpha(byte alpha) {
            return alpha == color.alpha() ? this : color.withAlpha(alpha);
        }

        @Override
        public byte red() {
            return color.red();
        }

        @Override
        public Color withRed(byte red) {
            return red == color.red() ? this : color.withRed(red);
        }

        @Override
        public byte green() {
            return color.green();
        }

        @Override
        public Color withGreen(byte green) {
            return green == color.green() ? this : color.withGreen(green);
        }

        @Override
        public byte blue() {
            return color.blue();
        }

        @Override
        public Color withBlue(byte blue) {
            return blue == color.blue() ? this : color.withBlue(blue);
        }
    }

    final class RgbInt24Color implements Color {
        private static final Random RANDOM = new Random();

        private final int rgb;

        private RgbInt24Color(int rgb) {
            this.rgb = rgb & 0x00ffffff;
        }

        public static RgbInt24Color of(int rgb) {
            return new RgbInt24Color(rgb);
        }

        public static RgbInt24Color of(byte red, byte green, byte blue) {
            return of(((int) red & 0xff) << 16
                    | ((int) green & 0xff) << 8
                    | (int) blue & 0xff);
        }

        public static RgbInt24Color random() {
            return new RgbInt24Color(RANDOM.nextInt(0x1000000));
        }

        @Override
        public int rgbInt24() {
            return rgb;
        }

        @Override
        public int argbInt32() {
            return 0xff000000 | rgb;
        }

        @Override
        public byte alpha() {
            return (byte) 0xff;
        }

        @Override
        public Color withAlpha(byte alpha) {
            return new ArgbInt32Color((int) alpha << 24 | rgb & 0x00ffffff);
        }

        @Override
        public byte red() {
            return (byte) (rgb >> 16);
        }

        @Override
        public Color withRed(byte red) {
            return red == red() ? this : new RgbInt24Color(rgb & 0xff0000 | ((int) red & 0xff) << 16);
        }

        @Override
        public byte green() {
            return (byte) (rgb >> 8);
        }

        @Override
        public Color withGreen(byte green) {
            return green == green() ? this : new RgbInt24Color(rgb & 0xff00ff | ((int) green & 0xff) << 8);
        }

        @Override
        public byte blue() {
            return (byte) rgb;
        }

        @Override
        public Color withBlue(byte blue) {
            return blue == blue() ? this : new RgbInt24Color(rgb & 0xffff00 | (int) blue & 0xff);
        }
    }

    final class ArgbInt32Color implements Color {
        private static final Random RANDOM = new Random();

        private final int argb;

        private ArgbInt32Color(int argb) {
            this.argb = argb;
        }

        public static ArgbInt32Color of(int argb) {
            return new ArgbInt32Color(argb);
        }

        public static ArgbInt32Color of(byte alpha, byte red, byte green, byte blue) {
            return of(((int) alpha & 0xff) << 24
                    | ((int) red & 0xff) << 16
                    | ((int) green & 0xff) << 8
                    | (int) blue & 0xff);
        }

        public static ArgbInt32Color random() {
            return of(RANDOM.nextInt());
        }

        @Override
        public int rgbInt24() {
            return argb;
        }

        @Override
        public int argbInt32() {
            return argb;
        }

        @Override
        public byte alpha() {
            return (byte) (argb >> 24);
        }

        @Override
        public Color withAlpha(byte alpha) {
            return alpha == alpha() ? this : new ArgbInt32Color(argb & 0x00ffffff | ((int) alpha & 0xff) << 24);
        }

        @Override
        public byte red() {
            return (byte) (argb >> 16);
        }

        @Override
        public Color withRed(byte red) {
            return red == red() ? this : new ArgbInt32Color(argb & 0xff00ffff | ((int) red & 0xff) << 16);
        }

        @Override
        public byte green() {
            return (byte) (argb >> 8);
        }

        @Override
        public Color withGreen(byte green) {
            return green == green() ? this : new ArgbInt32Color(argb & 0xffff00ff | ((int) green & 0xff) << 8);
        }

        @Override
        public byte blue() {
            return (byte) argb;
        }

        @Override
        public Color withBlue(byte blue) {
            return blue == blue() ? this : new ArgbInt32Color(argb & 0xffffff00 | (int) blue & 0xff);
        }
    }
}
