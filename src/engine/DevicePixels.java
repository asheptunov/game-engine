package engine;

/** Immutable device-resident display source. Each retained reference must be closed. */
public interface DevicePixels extends AutoCloseable {
    int width();

    int height();

    DevicePixels retain();

    /** Explicit export/readback, in top-down packed RGB order. */
    void copyDisplayTo(int[] destination, float exposure);

    /** Export with the same display-only bloom selected by the owning game frame. */
    default void copyDisplayTo(int[] destination, float exposure, Bloom bloom) {
        if (bloom != Bloom.OFF) {
            throw new UnsupportedOperationException("Bloom export");
        }
        copyDisplayTo(destination, exposure);
    }

    @Override
    void close();
}
