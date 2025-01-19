package rendering;

public interface RasterConverter {
    Raster forward(Raster raster);

    Raster backward(Raster raster);
}
