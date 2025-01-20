package rendering;

public interface Font {
    Raster getNil();

    Raster getChar(char c);

    interface Loader {
        Font load();
    }
}
