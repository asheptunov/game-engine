package rendering;

public class Checkerboard implements Renderer {
    private final Renderer delegate;

    public Checkerboard(float a1, float a2, Raster raster) {
        var checkerboard = new PixelRaster(raster.w(), raster.h(), (_, x, y)
                -> (x / 50) % 2 == 0
                ? (y / 50) % 2 == 0 ? Color.NamedColor.WHITE.withAlpha(a1) : Color.NamedColor.WHITE.withAlpha(a2)
                : (y / 50) % 2 == 0 ? Color.NamedColor.WHITE.withAlpha(a2) : Color.NamedColor.WHITE.withAlpha(a1));
        this.delegate = new ImageRenderer(checkerboard, raster, BlendMode.OVER_PRE);
    }

    @Override
    public void render() {
        delegate.render();
    }
}
