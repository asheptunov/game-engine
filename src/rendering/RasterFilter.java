package rendering;

public interface RasterFilter {
    Raster apply(Raster input);

    RasterFilter NO_OP = i -> i;
}
