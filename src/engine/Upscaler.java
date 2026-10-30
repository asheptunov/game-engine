package engine;

/** Temporal output reconstruction; scale is the fraction of each output dimension traced. */
public enum Upscaler {
    OFF(1),
    FSR_QUALITY(2. / 3),
    FSR_PERFORMANCE(.5);
    private final double scale;

    Upscaler(double scale) {
        this.scale = scale;
    }

    public double scale() {
        return scale;
    }
}
