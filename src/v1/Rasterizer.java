package v1;

public interface Rasterizer<T> {
    int[] rasterize(T value);
}
