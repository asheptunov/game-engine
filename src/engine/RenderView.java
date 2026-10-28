package engine;

/** Immutable camera and sensor-grid input. */
public record RenderView(Camera camera, int width, int height) {
    public static final int MIN_DIMENSION = 64;
    public static final int MAX_DIMENSION = 1920;

    public RenderView {
        if (camera == null) throw new IllegalArgumentException("Missing camera");
        camera.validated();
        validateDimensions(width, height);
    }

    public static void validateDimensions(int width, int height) {
        if (width < MIN_DIMENSION
                || width > MAX_DIMENSION
                || height < MIN_DIMENSION
                || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    "Render dimensions must each be " + MIN_DIMENSION + ".." + MAX_DIMENSION);
        }
    }
}
