package rendering;

public final class RasterFactory {
    private RasterFactory() {}

    public static Raster create(int width, int height) {
        return new PixelRaster(width, height);
    }

    public static Raster create(int width, int height, int[] pixels) {
        return new PixelRaster(width, height, pixels);
    }

    public static ScalableRaster scalable(Raster raster) {
        return raster instanceof ScalableRaster sr ? sr : scalable(raster, InterpolationType.LINEAR);
    }

    public enum InterpolationType {
        LINEAR
    }

    public static ScalableRaster scalable(Raster raster, InterpolationType type) {
        return switch (type) {
            case LINEAR -> new PixelRaster(raster);  // pixelRaster uses linear interpolation by default
        };
    }

    public static CloneableRaster cloneable(Raster raster) {
        return raster instanceof CloneableRaster cr ? cr : new PixelRaster(raster);
    }
}
