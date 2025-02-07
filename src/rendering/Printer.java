package rendering;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.synchronizedMap;

public interface Printer {
    void print(char c, int x, int y, Style... styles);

    void print(String str, int x, int y, Style... styles);

    Printer withRaster(Raster raster);

    sealed interface Style permits Size, Spacing, Color, BlendMode {}

    final class Size implements Style {
        private static final Map<Integer, Size> FLYWEIGHT = synchronizedMap(new HashMap<>());

        private final int size;

        private Size(int size) {
            this.size = size;
        }

        public static Size of(int size) {
            return FLYWEIGHT.computeIfAbsent(size, _ -> new Size(size));
        }

        public int size() {
            return size;
        }
    }

    final class Spacing implements Style {
        private static final Map<Integer, Spacing> FLYWEIGHT = synchronizedMap(new HashMap<>());

        private final int spacing;

        private Spacing(int spacing) {
            this.spacing = spacing;
        }

        public static Spacing of(int spacing) {
            return FLYWEIGHT.computeIfAbsent(spacing, _ -> new Spacing(spacing));
        }

        public int spacing() {
            return spacing;
        }
    }

    final class Color implements Style {
        private static final Map<rendering.Color, Color> FLYWEIGHT = synchronizedMap(new HashMap<>());

        private final rendering.Color color;

        private Color(rendering.Color color) {
            this.color = color;
        }

        public static Color of(rendering.Color color) {
            return FLYWEIGHT.computeIfAbsent(color, _ -> new Color(color));
        }

        public rendering.Color color() {
            return color;
        }
    }

    final class BlendMode implements Style {
        private static final Map<rendering.BlendMode, BlendMode> FLYWEIGHT = synchronizedMap(new HashMap<>());

        private final rendering.BlendMode blendMode;

        private BlendMode(rendering.BlendMode blendMode) {
            this.blendMode = blendMode;
        }

        public static BlendMode of(rendering.BlendMode blendMode) {
            return FLYWEIGHT.computeIfAbsent(blendMode, _ -> new BlendMode(blendMode));
        }

        public rendering.BlendMode blendMode() {
            return blendMode;
        }
    }
}
