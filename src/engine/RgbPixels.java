package engine;

/**
 * Read-only linear RGB access derived from a single-owner image lease. Use on the lease's
 * owner thread; reads and copies must finish before that enclosing lease is closed.
 */
public interface RgbPixels {
    int width();
    int height();
    float value(int channel,int x,int y);
    void copyTo(float[][][] destination);
}
