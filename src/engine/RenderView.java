package engine;

/** Immutable camera and sensor-grid input. */
public record RenderView(Camera camera, int width, int height) {
    public RenderView {
        if (camera == null) throw new IllegalArgumentException("Missing camera");
        camera.validated();
        if (width < 64 || width > 1600 || height < 64 || height > 1600)
            throw new IllegalArgumentException("Render dimensions must each be 64..1600");
    }
}
