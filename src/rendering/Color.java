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
    private final int    rgbValue;

    Color(String cmdName, int rgbValue) {
        this.cmdName = cmdName;
        this.rgbValue = rgbValue;
    }

    private static final Map<String, Color>  CMD_NAME_INDEX  = Arrays.stream(Color.values())
            .collect(Collectors.toMap(Color::getCmdName, c -> c));
    private static final Map<Integer, Color> RGB_VALUE_INDEX = Arrays.stream(Color.values())
            .collect(Collectors.toMap(Color::getRgbValue, c -> c));

    public static Optional<Color> fromCmdName(String cmdName) {
        return Optional.of(CMD_NAME_INDEX.get(cmdName.toLowerCase(Locale.ROOT)));
    }

    public static Optional<Color> fromRgbValue(int rgbValue) {
        return Optional.of(RGB_VALUE_INDEX.get(rgbValue));
    }

    public String getCmdName() {
        return cmdName;
    }

    public int getRgbValue() {
        return rgbValue;
    }

    public int getArgbValue() {
        return getArgbAtOpacity(0xff);
    }

    public int getArgbAtOpacity(byte opacity) {
        return (getRgbValue() << 8) | 0xff;
    }

    public int getArgbAtOpacity(double opacity) {
        return getArgbAtOpacity((byte) (Math.max(0, Math.min(1, opacity)) * 0xff));
    }
}
