package rendering;

public interface Font {
    Raster getChar(char c);

    Raster getNil();

    int size();
}
