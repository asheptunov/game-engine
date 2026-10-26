package engine;

/** Bounded one-entry optical-depth cache, independent of solar time and scattering coefficients. */
public final class AtmosphereTable {
    public static final int HEIGHTS = 96;
    public static final int DIRECTIONS = 192;
    public static final int FLOATS = HEIGHTS * DIRECTIONS * 2;

    private record Key(float radius, float extent, float molecularHeight, float aerosolHeight) {}

    private static volatile AtmosphereTable cached;
    private final Key key;
    private final float[] columns = new float[FLOATS];
    private final long preparationNanos;

    private AtmosphereTable(Atmosphere.Parameters parameters, Key key) {
        this.key = key;
        long start = System.nanoTime();
        var optical = new double[2];
        for (int y = 0; y < HEIGHTS; y++) {
            double h = key.extent * y * (double) y / ((HEIGHTS - 1.) * (HEIGHTS - 1.));
            double horizon = horizon(h);
            for (int x = 0; x < DIRECTIONS; x++) {
                double v = x / (DIRECTIONS - 1.);
                double mu = horizon + (1 - horizon) * v * v;
                // At the horizon use the outward side of the tangent, never a blocked sentinel.
                AtmosphereTransport.optical(parameters, h, Math.min(1, mu + 1e-8), 192, optical);
                int index = (y * DIRECTIONS + x) * 2;
                columns[index] = (float) optical[0];
                columns[index + 1] = (float) optical[1];
            }
        }
        preparationNanos = System.nanoTime() - start;
    }

    /** Called only in transport/preparation, never while holding an input or viewport monitor. */
    public static AtmosphereTable get(Atmosphere.Parameters p) {
        var value = cached;
        if (value != null && value.matches(p)) {
            return value;
        }
        return prepare(p);
    }

    private static synchronized AtmosphereTable prepare(Atmosphere.Parameters p) {
        var value = cached;
        if (value == null || !value.matches(p)) {
            value =
                    new AtmosphereTable(
                            p,
                            new Key(
                                    p.radiusKm(),
                                    p.heightKm(),
                                    p.molecularHeightKm(),
                                    p.aerosolHeightKm()));
            cached = value;
        }
        return value;
    }

    public boolean matches(Atmosphere.Parameters p) {
        return key.radius == p.radiusKm()
                && key.extent == p.heightKm()
                && key.molecularHeight == p.molecularHeightKm()
                && key.aerosolHeight == p.aerosolHeightKm();
    }

    private double horizon(double h) {
        return -Math.sqrt(h * (2 * key.radius + h)) / (key.radius + h);
    }

    boolean optical(double h, double mu, double[] result) {
        double horizon = horizon(h);
        if (mu < horizon || (h == 0 && mu < 0)) {
            return false;
        }
        double y = Math.sqrt(Math.clamp(h / key.extent, 0, 1)) * (HEIGHTS - 1);
        double x = Math.sqrt(Math.clamp((mu - horizon) / (1 - horizon), 0, 1)) * (DIRECTIONS - 1);
        int ix = Math.min((int) x, DIRECTIONS - 2);
        int iy = Math.min((int) y, HEIGHTS - 2);
        double u = x - ix;
        double v = y - iy;
        int a = (iy * DIRECTIONS + ix) * 2;
        for (int c = 0; c < 2; c++) {
            double low = columns[a + c] * (1 - u) + columns[a + 2 + c] * u;
            double high =
                    columns[a + DIRECTIONS * 2 + c] * (1 - u)
                            + columns[a + DIRECTIONS * 2 + 2 + c] * u;
            result[c] = low * (1 - v) + high * v;
        }
        return true;
    }

    public float column(int index) {
        return columns[index];
    }

    public long preparationNanos() {
        return preparationNanos;
    }

    public int payloadBytes() {
        return FLOATS * Float.BYTES;
    }
}
