package rendering;

public class RasterPainter implements Painter {
    private final Raster raster;

    public RasterPainter(Raster raster) {
        this.raster = raster;
    }

    @Override
    public void drawPoint(int x, int y, Color color, BlendMode blendMode) {
        fastDrawPoint(y * raster.w() + x, color, blendMode);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, LineSampler sampler, BlendMode blendMode) {
        int rise = Math.abs(y2 - y1);
        int run = Math.abs(x2 - x1);
        var length = (int) Math.sqrt(rise * rise + run * run);
        if (rise == 0 && run == 0) {
            drawPoint(x1, y1, sampler.apply(0, 0, 0.), blendMode);
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
                var color = sampler.apply(n++, length, progress);
                if (x >= 0 && x < raster.width() && y >= 0 && y < raster.height()) {
                    raster.pixel((int) x, y, color);
                }
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
                var color = sampler.apply(n++, length, progress);
                if (x >= 0 && x < raster.width() && y >= 0 && y < raster.height()) {
                    raster.pixel(x, (int) y, color);
                }
                y += slope;
                progress += progressionRate;
            }
        }
    }

    @Override
    public void drawImg(final int x, final int y, final int w, final int h, ImageSampler sampler, BlendMode blendMode) {
        int imgMinX = x < 0 ? -x : 0;
        int imgMinY = y < 0 ? -y : 0;
        int imgMaxX = w - Math.max(0, x + w - this.raster.width());
        int imgMaxY = h - Math.max(0, y + h - this.raster.height());
        int imgVisibleWidth = imgMaxX - imgMinX;
        int myStride = this.raster.width() - imgVisibleWidth;
        int imgStride = w - imgVisibleWidth;
        int myI = Math.max(0, y) * this.raster.width() + Math.max(0, x);
        int imgI = imgMinY * w + imgMinX;
        for (int imgY = imgMinY; imgY < imgMaxY; ++imgY) {
            for (int imgX = imgMinX; imgX < imgMaxX; ++imgX) {
                fastDrawPoint(myI, sampler.apply(imgI, imgX, imgY), blendMode);
                ++myI;
                ++imgI;
            }
            myI += myStride;
            imgI += imgStride;
        }
    }

    @Override
    public void drawTri(int x1, int y1, int x2, int y2, int x3, int y3, Color color, BlendMode blendMode) {
        throw new UnsupportedOperationException();
    }

    private void fastDrawPoint(int i, Color fg, BlendMode blendMode) {
        var bgA = raster.a()[i];
        var bgR = raster.r()[i];
        var bgG = raster.g()[i];
        var bgB = raster.b()[i];
        var bg = Color.ArgbInt32Color.of(bgA, bgR, bgG, bgB);
        var blended = blendMode.apply(fg, bg);
        raster.a()[i] = blended.a();
        raster.r()[i] = blended.r();
        raster.g()[i] = blended.g();
        raster.b()[i] = blended.b();
    }

//    private void fastDrawPoint(int i, Color c) {
//        float imgA = ((int) c.a() & 0xff) / 255f;
//        float imgR = ((int) c.r() & 0xff) / 255f;
//        float imgG = ((int) c.g() & 0xff) / 255f;
//        float imgB = ((int) c.b() & 0xff) / 255f;
//        float myA = ((int) raster.a()[i] & 0xff) / 255f;
//        float myR = ((int) raster.r()[i] & 0xff) / 255f;
//        float myG = ((int) raster.g()[i] & 0xff) / 255f;
//        float myB = ((int) raster.b()[i] & 0xff) / 255f;
//        float oneMinusImgA = 1 - imgA;
//        raster.a()[i] = (byte) (255. * Math.max(0, Math.min(1, (imgA + myA * oneMinusImgA))));
//        // straight
//        raster.r()[i] = (byte) (255. * (imgR * imgA + myR * myA * oneMinusImgA) / imgA);
//        raster.g()[i] = (byte) (255. * (imgG * imgA + myG * myA * oneMinusImgA) / imgA);
//        raster.b()[i] = (byte) (255. * (imgB * imgA + myB * myA * oneMinusImgA) / imgA);
//        // pre-multiplied
//        raster.r()[i] = (byte) (255. * Math.max(0, Math.min(1, (imgR + myR * oneMinusImgA))));
//        raster.g()[i] = (byte) (255. * Math.max(0, Math.min(1, (imgG + myG * oneMinusImgA))));
//        raster.b()[i] = (byte) (255. * Math.max(0, Math.min(1, (imgB + myB * oneMinusImgA))));
//    }
}
