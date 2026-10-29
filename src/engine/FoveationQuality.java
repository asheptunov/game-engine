package engine;

/** Smooth GPU lattice controls: central fraction of each axis and outer spacing in pixels. */
public record FoveationQuality(double centerFraction, int edgeSpacing) {
    public FoveationQuality {
        if (!Double.isFinite(centerFraction) || centerFraction < .1 || centerFraction > .75) {
            throw new IllegalArgumentException("Sharp center must be 10..75 percent of each axis");
        }
        if (edgeSpacing < 2 || edgeSpacing > 16) {
            throw new IllegalArgumentException("Peripheral spacing must be 2..16 pixels");
        }
    }

    public static FoveationQuality defaults() {
        return new FoveationQuality(.5, 6);
    }
}
