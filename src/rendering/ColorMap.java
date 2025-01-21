package rendering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ColorMap implements RasterFilter {
    private static final Map<Color, Map<Color, ColorMap>> FLYWEIGHT = Collections.synchronizedMap(new HashMap<>());

    private final Color from;
    private final Color to;

    private ColorMap(Color from, Color color) {
        this.from = from;
        to = color;
    }

    public static ColorMap of(Color from, Color to) {
        return FLYWEIGHT.computeIfAbsent(from, _ -> Collections.synchronizedMap(new HashMap<>()))
                .computeIfAbsent(to, _ -> new ColorMap(from, to));
    }

    @Override
    public Raster apply(Raster input) {
        var res = input.clone();
        var r = res.red();
        var g = res.green();
        var b = res.blue();
        for (int i = 0; i < res.width() * res.height(); ++i) {
            if (r[i] == from.red() && g[i] == from.green() && b[i] == from.blue()) {
                r[i] = to.red();
                g[i] = to.green();
                b[i] = to.blue();
            }
        }
        return res;
    }
}
