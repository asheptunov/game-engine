package rendering;

import logging.LogManager;
import logging.Logger;

public class GradientRenderer implements Renderer {
    private static final Logger LOG = LogManager.instance().getThis();

    private final int[]  from;
    private final int[]  to;
    private final Raster raster;

    public GradientRenderer(Raster raster, int fromColor, int toColor) {
        from = decompose(fromColor);
        to = decompose(toColor);
        LOG.debug("Will make gradients from 0x%x to 0x%x", fromColor, toColor);
        this.raster = raster;
    }

    @Override
    public void render() {
        var pixels = this.raster.pixels();
        for (int i = 0; i < pixels.length; i += 3) {
            var pct = 1. * i / pixels.length;
            pixels[i] = (int) (from[0] + pct * (to[0] - from[0]));
            pixels[i + 1] = (int) (from[1] + pct * (to[1] - from[1]));
            pixels[i + 2] = (int) (from[2] + pct * (to[2] - from[2]));
        }
    }

    private static int[] decompose(int fromColor) {
        return new int[]{
                (fromColor & 0xff0000) >> 16,
                (fromColor & 0xff00) >> 8,
                fromColor & 0xff
        };
    }
}
