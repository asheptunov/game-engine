package rendering;

public class Eraser implements Renderer {
    private final Raster raster;

    public Eraser(Raster raster) {
        this.raster = raster;
    }

    @Override
    public void render() {
        raster.write(0, 0, raster.w(), raster.h(), (_, _, _) -> Color.NamedColor.NONE);
    }
}
