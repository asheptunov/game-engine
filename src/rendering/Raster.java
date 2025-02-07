package rendering;

public interface Raster {
    int width();

    int height();

    default int w() {
        return width();
    }

    default int h() {
        return height();
    }

    int[] rgb();

    int[] argb();

    byte[] alpha();

    byte[] red();

    byte[] green();

    byte[] blue();

    default byte[] a() {
        return alpha();
    }

    default byte[] r() {
        return red();
    }

    default byte[] g() {
        return green();
    }

    default byte[] b() {
        return blue();
    }

    @FunctionalInterface
    interface Reader<T> {
        T apply(int x, int y, Color c);

        Reader<Color> READ_COLOR = (_, _, c) -> c;
    }

    void read(int x, int y, int w, int h, Reader<?> reader);

    default <T> void read(Reader<T> reader) {
        read(0, 0, w(), h(), reader);
    }

    @FunctionalInterface
    interface Readable<T> {
        T next(Reader<T> scanner);
    }

    <T> Readable<T> read();

    @FunctionalInterface
    interface Writer {
        Color write(int i, int x, int y);
    }

    interface Writeable {
        void next(Writer writer);
    }

    default void write(Writer writer) {
        write(0, 0, w(), h(), writer);
    }

    void write(int x, int y, int w, int h, Writer writer);

    Color pixel(int x, int y);

    void pixel(int x, int y, Color color);

    Raster scale(int width, int height);

    Raster clone();
}
