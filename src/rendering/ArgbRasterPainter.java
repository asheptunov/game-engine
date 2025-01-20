package rendering;

public class ArgbRasterPainter extends AbstractRasterPainter<ArgbPixelRaster> implements Painter {
    public ArgbRasterPainter(ArgbPixelRaster raster) {
        super(raster);
    }

    @Override
    public void drawImg(int x, int y, Raster imgRaster) {
        if (!(imgRaster instanceof ArgbPixelRaster)) {
            throw new IllegalArgumentException(imgRaster.getClass().getName());
        }
        int imgMinX = x < 0 ? -x : 0;
        int imgMinY = y < 0 ? -y : 0;
        int imgMaxX = imgRaster.width() - Math.max(0, x + imgRaster.width() - this.raster.width());
        int imgMaxY = imgRaster.height() - Math.max(0, y + imgRaster.height() - this.raster.height());
        int imgVisibleWidth = imgMaxX - imgMinX;
        int myStride = 4 * (this.raster.width() - imgVisibleWidth);
        int imgStride = 4 * (imgRaster.width() - imgVisibleWidth);
        int myI = 4 * (Math.max(0, y) * this.raster.width() + Math.max(0, x));
        int imgI = 4 * (imgMinY * imgRaster.width() + imgMinX);
        var pixels = this.raster.pixels();
        var imgPixels = imgRaster.pixels();
        for (int imgY = imgMinY; imgY < imgMaxY; ++imgY) {
            for (int imgX = imgMinX; imgX < imgMaxX; ++imgX) {
                var myAlpha = 1. * pixels[myI + 3] / 0xff;
                // pre-multiplied alpha blending
                for (int i = 0; i < 4; ++i) {
                    pixels[myI] = pixels[myI] + (int) (imgPixels[imgI++] * (1 - myAlpha));
//                    pixels[myI] = Math.max(0, Math.min(0xff, pixels[myI] + (int) (imgPixels[imgI++] * (1 - myAlpha))));
                    ++myI;
                }
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
