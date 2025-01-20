package rendering;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum Color {
    BLACK("black", 0x0),
    WHITE("white", 0xffffff),
    RED("red", 0xff0000),
    GREEN("green", 0xff00),
    BLUE("blue", 0xff);

    private final String cmdName;
    private final int    rgb;

    Color(String cmdName, int rgb) {
        this.cmdName = cmdName;
        this.rgb = rgb;
    }

    private static final Map<String, Color>  CMD_NAME_INDEX  = Arrays.stream(Color.values())
            .collect(Collectors.toMap(Color::getCmdName, c -> c));
    private static final Map<Integer, Color> RGB_VALUE_INDEX = Arrays.stream(Color.values())
            .collect(Collectors.toMap(Color::getRgb, c -> c));

    public static Optional<Color> fromCmdName(String cmdName) {
        return Optional.of(CMD_NAME_INDEX.get(cmdName.toLowerCase(Locale.ROOT)));
    }

    public static Optional<Color> fromRgb(int rgb) {
        return Optional.of(RGB_VALUE_INDEX.get(rgb));
    }

    public String getCmdName() {
        return cmdName;
    }

    public int getRgb() {
        return rgb;
    }

    public int getArgb() {
        return getArgbAtOpacity((byte) 0xff);
    }

    public int getArgbAtOpacity(double alpha) {
        return getArgbAtOpacity((byte) Math.max(0, Math.min(1, alpha * 0xff)));
    }

    public int getArgbAtOpacity(byte alpha) {
        return (alpha << 24) | (rgb & 0xffffff);
    }
}
