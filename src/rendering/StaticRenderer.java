package rendering;

public class StaticRenderer implements Renderer {
    private final Raster raster;
    private final int    color;

    public StaticRenderer(int color, Raster raster) {
        this.raster = raster;
        this.color = color;
    }

    @Override
    public void render() {
        int h = raster.height();
        int w = raster.width();
        var pixels = raster.pixels();
        int i = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                pixels[i++] = (byte) (color >> 24);  // red
                pixels[i++] = (byte) (color >> 16);  // green
                pixels[i++] = (byte) (color >> 8);  // blue
                pixels[i++] = (byte) color;  // alpha
            }
        }
    }
}
