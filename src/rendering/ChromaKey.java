package rendering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ChromaKey implements RasterFilter {
    private static final Map<Color, ChromaKey> FLYWEIGHT = Collections.synchronizedMap(new HashMap<>());

    private final Color color;

    private ChromaKey(Color color) {
        this.color = color;
    }

    public static ChromaKey of(Color color) {
        return FLYWEIGHT.computeIfAbsent(color, _ -> new ChromaKey(color));
    }

    @Override
    public Raster apply(Raster input) {
        var res = input.clone();
        var a = res.alpha();
        var r = res.red();
        var g = res.green();
        var b = res.blue();
        for (int i = 0; i < res.width() * res.height(); ++i) {
            if (r[i] == color.red() && g[i] == color.green() && b[i] == color.blue()) {
                a[i] = 0;
            }
        }
        return res;
    }
}
