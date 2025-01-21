package rendering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public interface Printer {
    void print(char c, int x, int y, Style... styles);

    void print(String str, int x, int y, Style... styles);

    sealed interface Style permits Size, Spacing, Color {}

    final class Size implements Style {
        private static final Map<Integer, Size> FLYWEIGHT = Collections.synchronizedMap(new HashMap<>());

        private final int size;

        private Size(int size) {this.size = size;}

        public static Size of(int size) {
            return FLYWEIGHT.computeIfAbsent(size, _ -> new Size(size));
        }

        public int size() {return size;}
    }

    final class Spacing implements Style {
        private static final Map<Integer, Spacing> FLYWEIGHT = Collections.synchronizedMap(new HashMap<>());

        private final int spacing;

        private Spacing(int spacing) {this.spacing = spacing;}

        public static Spacing of(int spacing) {
            return FLYWEIGHT.computeIfAbsent(spacing, _ -> new Spacing(spacing));
        }

        public int spacing() {return spacing;}
    }

    final class Color implements Style {
        private static final Map<rendering.Color, Color> FLYWEIGHT = Collections.synchronizedMap(new HashMap<>());

        private final rendering.Color color;

        private Color(rendering.Color color) {this.color = color;}

        public static Color of(rendering.Color color) {
            return FLYWEIGHT.computeIfAbsent(color, _ -> new Color(color));
        }

        public rendering.Color color() {return color;}
    }
}
