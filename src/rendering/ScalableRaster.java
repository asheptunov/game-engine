package rendering;

public interface ScalableRaster extends Raster {
    ScalableRaster scale(int toWidth, int toHeight);
}
