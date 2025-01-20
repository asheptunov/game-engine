package rendering;

public class RgbRasterPainter extends AbstractRasterPainter<RgbPixelRaster> implements Painter {
    public RgbRasterPainter(RgbPixelRaster raster) {
        super(raster);
    }

    @Override
    public void drawImg(final int x, final int y, Raster imgRaster) {
        if (!(imgRaster instanceof RgbPixelRaster)) {
            throw new IllegalArgumentException(imgRaster.getClass().getName());
        }
        int imgMinX = x < 0 ? -x : 0;
        int imgMinY = y < 0 ? -y : 0;
        int imgMaxX = imgRaster.width() - Math.max(0, x + imgRaster.width() - this.raster.width());
        int imgMaxY = imgRaster.height() - Math.max(0, y + imgRaster.height() - this.raster.height());
        int imgVisibleWidth = imgMaxX - imgMinX;
        int myStride = 3 * (this.raster.width() - imgVisibleWidth);
        int imgStride = 3 * (imgRaster.width() - imgVisibleWidth);
        int myI = 3 * (Math.max(0, y) * this.raster.width() + Math.max(0, x));
        int imgI = 3 * (imgMinY * imgRaster.width() + imgMinX);
        var pixels = this.raster.pixels();
        var imgPixels = imgRaster.pixels();
        for (int imgY = imgMinY; imgY < imgMaxY; ++imgY) {
            for (int imgX = imgMinX; imgX < imgMaxX; ++imgX) {
                pixels[myI++] = imgPixels[imgI++];
                pixels[myI++] = imgPixels[imgI++];
                pixels[myI++] = imgPixels[imgI++];
            }
            myI += myStride;
            imgI += imgStride;
        }
    }

    @Override
    public void drawTri(int x1, int y1, int x2, int y2, int x3, int y3, int edgeColor, int fillColor) {
        throw new UnsupportedOperationException();
    }
}
