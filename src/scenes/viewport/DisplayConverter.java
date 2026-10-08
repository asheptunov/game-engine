package scenes.viewport;

import engine.DisplayMapping;
import engine.RgbPixels;

import rendering.Raster;

import java.util.Arrays;

/** Display-thread-owned box filter and encoded cache. No steady-state pixel allocation. */
public final class DisplayConverter {
    private final int width, height;
    private final byte[][] encoded;
    private int sensorW, sensorH;
    private Axis xAxis, yAxis;
    private float[][][] leaseCopy;

    public DisplayConverter(int width, int height) {
        this.width = width;
        this.height = height;
        encoded = new byte[3][width * height];
    }

    /** Copy leased engine storage into a reusable display-owned buffer before conversion. */
    public void convert(RgbPixels rgb, float exposure) {
        int h = rgb.height(), w = rgb.width();
        if (leaseCopy == null || leaseCopy[0].length != h || leaseCopy[0][0].length != w)
            leaseCopy = new float[3][h][w];
        rgb.copyTo(leaseCopy);
        convert(leaseCopy, exposure);
    }

    /**
     * Keeps the reference's float arithmetic and source iteration order, including Y orientation.
     */
    public void convert(float[][][] rgb, float exposure) {
        int h = rgb[0].length, w = rgb[0][0].length;
        if (w != sensorW || h != sensorH) {
            sensorW = w;
            sensorH = h;
            if (!(width % w == 0 && height % h == 0) && !(w % width == 0 && h % height == 0)) {
                xAxis = new Axis(w, width);
                yAxis = new Axis(h, height);
            } else {
                xAxis = null;
                yAxis = null;
            }
        }
        if (width == w && height == h) {
            for (int sy = 0; sy < h; sy++) {
                int start = (height - 1 - sy) * width;
                var r = rgb[0][sy];
                var g = rgb[1][sy];
                var b = rgb[2][sy];
                for (int sx = 0; sx < w; sx++) {
                    encoded[0][start + sx] = DisplayMapping.encode(r[sx], exposure);
                    encoded[1][start + sx] = DisplayMapping.encode(g[sx], exposure);
                    encoded[2][start + sx] = DisplayMapping.encode(b[sx], exposure);
                }
            }
            return;
        }
        if (width % w == 0 && height % h == 0) {
            int bw = width / w, bh = height / h;
            for (int sy = 0; sy < h; sy++) {
                int row = (height - (sy + 1) * bh) * width;
                for (int sx = 0; sx < w; sx++) {
                    int start = row + sx * bw;
                    Arrays.fill(
                            encoded[0],
                            start,
                            start + bw,
                            DisplayMapping.encode(rgb[0][sy][sx], exposure));
                    Arrays.fill(
                            encoded[1],
                            start,
                            start + bw,
                            DisplayMapping.encode(rgb[1][sy][sx], exposure));
                    Arrays.fill(
                            encoded[2],
                            start,
                            start + bw,
                            DisplayMapping.encode(rgb[2][sy][sx], exposure));
                }
                for (int by = 1; by < bh; by++)
                    for (int c = 0; c < 3; c++)
                        System.arraycopy(encoded[c], row, encoded[c], row + by * width, width);
            }
            return;
        }
        boolean integerReduction = w % width == 0 && h % height == 0;
        int bw = w / width, bh = h / height;
        for (int dy = 0; dy < height; dy++)
            for (int dx = 0; dx < width; dx++) {
                float r = 0, g = 0, b = 0, total = 0;
                if (integerReduction) {
                    for (int sy = dy * bh; sy < (dy + 1) * bh; sy++)
                        for (int sx = dx * bw; sx < (dx + 1) * bw; sx++) {
                            r += rgb[0][sy][sx];
                            g += rgb[1][sy][sx];
                            b += rgb[2][sy][sx];
                        }
                    total = bw * bh;
                } else {
                    var wx = xAxis.weights[dx];
                    var wy = yAxis.weights[dy];
                    for (int yi = 0; yi < wy.length; yi++) {
                        int sy = yAxis.first[dy] + yi;
                        for (int xi = 0; xi < wx.length; xi++) {
                            int sx = xAxis.first[dx] + xi;
                            float weight = wx[xi] * wy[yi];
                            r += rgb[0][sy][sx] * weight;
                            g += rgb[1][sy][sx] * weight;
                            b += rgb[2][sy][sx] * weight;
                            total += weight;
                        }
                    }
                }
                int i = (height - 1 - dy) * width + dx;
                encoded[0][i] = DisplayMapping.encode(total > 0 ? r / total : 0, exposure);
                encoded[1][i] = DisplayMapping.encode(total > 0 ? g / total : 0, exposure);
                encoded[2][i] = DisplayMapping.encode(total > 0 ? b / total : 0, exposure);
            }
    }

    /** Restore the clean background every frame: UI and overlays may have modified the raster. */
    public void paint(Raster display) {
        if (display.width() != width || display.height() != height)
            throw new IllegalArgumentException("Display dimensions changed");
        Arrays.fill(display.alpha(), (byte) 255);
        System.arraycopy(encoded[0], 0, display.red(), 0, width * height);
        System.arraycopy(encoded[1], 0, display.green(), 0, width * height);
        System.arraycopy(encoded[2], 0, display.blue(), 0, width * height);
    }

    private static final class Axis {
        final int[] first;
        final float[][] weights;

        Axis(int source, int destination) {
            first = new int[destination];
            weights = new float[destination][];
            float scale = (float) source / destination;
            for (int d = 0; d < destination; d++) {
                float start = d * scale, end = (d + 1) * scale;
                int from = (int) Math.floor(start),
                        to = Math.min(source - 1, (int) Math.ceil(end) - 1);
                first[d] = from;
                weights[d] = new float[to - from + 1];
                for (int s = from; s <= to; s++)
                    weights[d][s - from] = Math.min(s + 1, end) - Math.max(s, start);
            }
        }
    }
}
