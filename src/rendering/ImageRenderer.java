package rendering;

public class ImageRenderer implements Renderer {
    private final Raster    image;
    private final Painter   painter;
    private final BlendMode blendMode;

    public ImageRenderer(Raster image, Raster raster, BlendMode blendMode) {
        this.image = image;
        this.painter = new RasterPainter(raster);
        this.blendMode = blendMode;
    }

    public ImageRenderer(Color color, Raster raster, BlendMode blendMode) {
        this(new PixelRaster(raster.w(), raster.h(), (_, _, _) -> color), raster, blendMode);
    }

    @Override
    public void render() {
        painter.drawImg(0, 0, image, blendMode);
    }
}
