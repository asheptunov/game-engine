package rendering;

public interface Renderer {
    interface Context {
        Raster raster();

        Painter painter();

        Printer printer();
    }

    void render(Context context);
}
