package engine;

/** Immutable device-resident display source. Each retained reference must be closed. */
public interface DevicePixels extends AutoCloseable {
    int width();

    int height();

    DevicePixels retain();

    /** Explicit export/readback, in top-down packed RGB order. */
    void copyDisplayTo(int[] destination, float exposure);

    @Override
    void close();
}
