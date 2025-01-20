package rendering;

public class ChromaKey implements RasterFilter {
    private final int color;

    public ChromaKey(int color) {
        this.color = color & 0x00ffffff;  // filter only based on chroma, not alpha
    }

    @Override
    public Raster apply(Raster input) {
        var res = input.clone();
        var a = res.alpha();
        var r = res.red();
        var g = res.green();
        var b = res.blue();
        for (int i = 0; i < res.width() * res.height(); ++i) {
            if (color == ((((int) r[i] & 0xff) << 16) | (((int) g[i] & 0xff) << 8) | ((int) b[i] & 0xff))) {
                a[i] = 0;
            } else {
                a[i] = (byte)90;
            }
        }
        return res;
    }
}
