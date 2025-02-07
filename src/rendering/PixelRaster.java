package rendering;

public class PixelRaster implements Raster {
    private final int    w;
    private final int    h;
    private final byte[] a;
    private final byte[] r;
    private final byte[] g;
    private final byte[] b;

    public PixelRaster(Raster other) {
        this(other.width(), other.height(), other.alpha(), other.red(), other.green(), other.blue());
    }

    public PixelRaster(int width, int height, Painter.ImageSampler imageSampler) {
        this(width, height, initBytes(width, height, imageSampler));
    }

    public PixelRaster(int width, int height, byte[][] bytes) {
        this(width, height, bytes[0], bytes[1], bytes[2], bytes[3]);
    }

    public PixelRaster(int width, int height, byte[] alpha, byte[] red, byte[] green, byte[] blue) {
        this.w = width;
        this.h = height;
        this.a = alpha;
        this.r = red;
        this.g = green;
        this.b = blue;
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public int[] rgb() {
        int n = w * h;
        var res = new int[n * 3];
        int resI = 0;
        for (int i = 0; i < n; ++i) {
            var alpha = ((int) a[i] & 0xff) / 255.;
            res[resI++] = ((int) (alpha * ((int) r[i] & 0xff))) & 0xff;
            res[resI++] = ((int) (alpha * ((int) g[i] & 0xff))) & 0xff;
            res[resI++] = ((int) (alpha * ((int) b[i] & 0xff))) & 0xff;
        }
        return res;
    }

    @Override
    public int[] argb() {
        int n = w * h;
        var res = new int[n * 4];
        int resI = 0;
        for (int i = 0; i < n; ++i) {
            res[resI++] = (int) a[i] & 0xff;
            res[resI++] = (int) r[i] & 0xff;
            res[resI++] = (int) g[i] & 0xff;
            res[resI++] = (int) b[i] & 0xff;
        }
        return res;
    }

    @Override
    public byte[] alpha() {
        return a;
    }

    @Override
    public byte[] red() {
        return r;
    }

    @Override
    public byte[] green() {
        return g;
    }

    @Override
    public byte[] blue() {
        return b;
    }

    @Override
    public void read(int x, int y, int w, int h, Reader<?> reader) {
        int i = 0;
        for (int r = Math.max(0, y); r < Math.min(this.h, h); ++r) {
            for (int c = Math.min(0, x); c < Math.max(this.w, w); ++c) {
                reader.apply(x, y, Color.ArgbInt32Color.of(this.a[i], this.r[i], this.g[i], this.b[i]));
                ++i;
            }
        }
    }

    @Override
    public <T> Readable<T> read() {
        return new Readable<T>() {
            private int i = 0;
            private int x = 0;
            private int y = 0;

            @Override
            public T next(Reader<T> reader) {
                T res = reader.apply(x, y, Color.ArgbInt32Color.of(a[i], r[i], g[i], b[i]));
                if (x == w) {
                    x = 0;
                    ++y;
                } else {
                    ++x;
                }
                ++i;
                return res;
            }
        };
    }

    @Override
    public void write(int x, int y, int w, int h, Writer writer) {
        int i = 0;
        for (int r = Math.max(0, y); r < Math.min(y + h, this.h); ++r) {
            for (int c = Math.max(0, x); c < Math.min(x + w, this.w); ++c) {
                var color = writer.write(i, c, r);
                this.a[i] = color.a();
                this.r[i] = color.r();
                this.g[i] = color.g();
                this.b[i] = color.b();
                ++i;
            }
        }
    }

    @Override
    public Color pixel(int x, int y) {
        int i = y * w + x;
        return Color.ArgbInt32Color.of(a[i], r[i], g[i], b[i]);
    }

    @Override
    public void pixel(int x, int y, Color color) {
        int i = y * w + x;
        int c = color.argbInt32();
        a[i] = (byte) (c >> 24);
        r[i] = (byte) (c >> 16);
        g[i] = (byte) (c >> 8);
        b[i] = (byte) c;
    }

    @Override
    public PixelRaster scale(int w, int h) {
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException();
        }
        double xScale = 1. * this.w / w;
        double yScale = 1. * this.h / h;
        return new PixelRaster(w, h, (_, x, y) -> {
            int i = ((int) (yScale * y) * this.w) + (int) (xScale * x);
            return Color.ArgbInt32Color.of(a[i], r[i], g[i], b[i]);
        });
    }

    @Override
    public PixelRaster clone() {
        return new PixelRaster(w, h, cloneBytes(a), cloneBytes(r), cloneBytes(g), cloneBytes(b));
    }

    private static byte[][] initBytes(int width, int height, Painter.ImageSampler color) {
        var res = new byte[4][width * height];
        int i = 0;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int c = color.apply(i, x, y).argbInt32();
                res[0][i] = (byte) (c >> 24);  // alpha
                res[1][i] = (byte) (c >> 16);  // red
                res[2][i] = (byte) (c >> 8);  // green
                res[3][i] = (byte) c;  // blue
                ++i;
            }
        }
        return res;
    }

    private static byte[] cloneBytes(byte[] bytes) {
        int n = bytes.length;
        var res = new byte[n];
        System.arraycopy(bytes, 0, res, 0, n);
        return res;
    }
}
