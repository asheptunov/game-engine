package rendering;

import di.annotations.Inject;

public class Eraser implements Renderer {
    private final Raster raster;

    @Inject
    public Eraser(Raster raster) {
        this.raster = raster;
    }

    @Override
    public void render() {
        raster.write(0, 0, raster.w(), raster.h(), (_, _, _) -> Color.NamedColor.NONE);
    }
}
