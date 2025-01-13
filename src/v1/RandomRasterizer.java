package v1;

import java.util.Random;

public class RandomRasterizer<T> implements Rasterizer<T> {
    private final int[] raster;

    public RandomRasterizer(int width, int height) {
        this.raster = new int[width * height];
    }

    @Override
    public int[] rasterize(T value) {
        var random = new Random();
        for (int i = 0; i < raster.length; ++i) {
            raster[i] = random.nextInt(0xff);
        }
        return raster;
    }
}
