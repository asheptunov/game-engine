package rendering;

import java.util.function.BiFunction;

public abstract class AbstractRasterPainter<R extends Raster> implements Painter {
    protected final R raster;

    protected AbstractRasterPainter(R raster) {
        this.raster = raster;
    }

    @Override
    public void drawPoint(int x, int y, int color) {
        raster.setPixel(x, y, color);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, int color) {
        drawLine(x1, y1, x2, y2, (i, d) -> color);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, BiFunction<Integer, Double, Integer> pattern) {
        int rise = Math.abs(y2 - y1);
        int run = Math.abs(x2 - x1);
        if (rise == 0 && run == 0) {
            drawPoint(x1, y1, pattern.apply(0, 0.));
        }
        if (rise > run) {
            int startY, endY, startX, endX;
            if (y2 > y1) {
                startY = y1;
                startX = x1;
                endY = y2;
                endX = x2;
            } else {
                startY = y2;
                startX = x2;
                endY = y1;
                endX = x1;
            }
            double slope = 1. * (endX - startX) / (endY - startY);
            double x = startX;
            double progressionRate = 1. / (endY - startY);
            double progress = 0.;
            int n = 0;
            // can do this more accurately using pythagorean, but much slower.
            // maybe amortize by periodically recomputing basis with pythagorean, if accuracy is bad for long runs.
            for (int y = startY; y < endY; ++y) {
                // rounding might be more accurate, but maybe slower
                int color = pattern.apply(n++, progress);
                raster.setPixel((int) x, y, color);
                x += slope;
                progress += progressionRate;
            }
        } else {
            int startX, endX, startY, endY;
            if (x2 > x1) {
                startX = x1;
                startY = y1;
                endX = x2;
                endY = y2;
            } else {
                startX = x2;
                startY = y2;
                endX = x1;
                endY = y1;
            }
            double slope = 1. * (endY - startY) / (endX - startX);
            double y = startY;
            double progressionRate = 1. / (endX - startX);
            double progress = 0.;
            int n = 0;
            for (int x = startX; x < endX; ++x) {
                int color = pattern.apply(n++, progress);
                raster.setPixel(x, (int) y, color);
                y += slope;
                progress += progressionRate;
            }
        }
    }
}
