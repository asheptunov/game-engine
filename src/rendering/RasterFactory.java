package rendering;

public final class RasterFactory {
    private final int channels;

    // todo flyweight this
    public RasterFactory(int channels) {
        this.channels = channels;
    }

    public Raster create(int width, int height) {
        return switch (channels) {
            case 3 -> new RgbPixelRaster(width, height, this);
            case 4 -> new ArgbPixelRaster(width, height, this);
            default -> throw new IllegalArgumentException();
        };
    }

    public Raster create(int width, int height, int[] pixels) {
        if (width * height * channels != pixels.length) {
            throw new IllegalArgumentException();
        }
        return switch (channels) {
            case 3 -> new RgbPixelRaster(width, height, pixels, this);
            case 4 -> new ArgbPixelRaster(width, height, pixels, this);
            default -> throw new IllegalArgumentException();
        };
    }

    public ScalableRaster scalable(Raster raster) {
        return (ScalableRaster) raster;
    }

    public CloneableRaster cloneable(Raster raster) {
        return (CloneableRaster) raster;
    }
}
