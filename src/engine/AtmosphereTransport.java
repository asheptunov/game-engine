package engine;

/**
 * Spherical exponential atmosphere. All integration distances are km. Scratch belongs to a tracing
 * worker; no transport allocation or cache keyed by moving time/camera. GPU mirrors these
 * equations.
 */
public final class AtmosphereTransport {
    public static final int VIEW_STEPS = 32;
    public static final int SUN_STEPS = 0;
    private static final double PI = Math.PI;

    public record Quality(int viewSteps, int sunSteps, boolean multiple) {
        public Quality {
            if (viewSteps < 1 || sunSteps < 0) {
                throw new IllegalArgumentException(
                        "Integration steps must be positive (sun=0 selects lookup)");
            }
        }
    }

    public static final Quality PRODUCTION = new Quality(VIEW_STEPS, SUN_STEPS, true);

    private AtmosphereTransport() {}

    public static final class Scratch {
        private final Quality quality;

        public Scratch() {
            this(PRODUCTION);
        }

        public Scratch(Quality quality) {
            this.quality = java.util.Objects.requireNonNull(quality);
        }

        public final float[] radiance = new float[3];
        public final float[] transmission = new float[3];
        public final float[] sunlight = new float[3];
        private AtmosphereTable table;
        private final double[] optical = new double[2];
        private final double[] secondary = new double[2];
    }

    public static double altitude(Atmosphere.Parameters p, double y) {
        return Math.clamp((y - p.groundY()) * p.metersPerUnit() * .001, 0, p.heightKm());
    }

    /** Stable height along a spherical ray, avoiding subtraction of two planet-sized squares. */
    private static double height(Atmosphere.Parameters p, double h, double mu, double t) {
        double delta = h * (2 * p.radiusKm() + h) + t * (2 * (p.radiusKm() + h) * mu + t);
        return delta / (Math.sqrt(p.radiusKm() * (double) p.radiusKm() + delta) + p.radiusKm());
    }

    static double boundary(Atmosphere.Parameters p, double h, double mu, boolean ground) {
        double r = p.radiusKm() + h;
        double c =
                ground
                        ? h * (2 * p.radiusKm() + h)
                        : (h - p.heightKm()) * (2 * p.radiusKm() + h + p.heightKm());
        double discriminant = r * r * mu * mu - c;
        if (ground) {
            return mu < 0 && discriminant >= 0
                    ? Math.max(0, -r * mu - Math.sqrt(discriminant))
                    : Double.POSITIVE_INFINITY;
        }
        return Math.max(0, -r * mu + Math.sqrt(Math.max(0, discriminant)));
    }

    static boolean optical(
            Atmosphere.Parameters p, double h, double mu, int steps, double[] result) {
        if (boundary(p, h, mu, true) < Double.POSITIVE_INFINITY) {
            result[0] = result[1] = Double.POSITIVE_INFINITY;
            return false;
        }
        double length = boundary(p, h, mu, false);
        result[0] = result[1] = 0;
        for (int i = 0; i < steps; i++) {
            // Quadratic intervals resolve the dense air nearest the observer.
            double a = length * i * i / (steps * (double) steps);
            double b = length * (i + 1) * (i + 1) / (steps * (double) steps);
            double altitude = Math.max(0, height(p, h, mu, (a + b) * .5));
            result[0] += Math.exp(-altitude / p.molecularHeightKm()) * (b - a);
            result[1] += Math.exp(-altitude / p.aerosolHeightKm()) * (b - a);
        }
        return true;
    }

    private static boolean sampleOptical(
            Atmosphere.Parameters p,
            double h,
            double mu,
            int steps,
            double[] result,
            Scratch scratch) {
        if (steps != 0) {
            return optical(p, h, mu, steps, result);
        }
        if (scratch.table == null || !scratch.table.matches(p)) {
            scratch.table = AtmosphereTable.get(p);
        }
        return scratch.table.optical(h, mu, result);
    }

    private static double channel(math.Vec3 v, int c) {
        return c == 0 ? v.x() : c == 1 ? v.y() : v.z();
    }

    private static double extinction(
            Atmosphere.Parameters p, int c, double molecular, double aerosol) {
        return (channel(p.molecularScattering(), c) + channel(p.absorption(), c)) * molecular
                + channel(p.aerosolScattering(), c) * aerosol;
    }

    public static void sunlight(Atmosphere air, float y, Scratch scratch) {
        var p = air.parameters();
        double h = altitude(p, y);
        double horizon = -Math.sqrt(h * (2 * p.radiusKm() + h)) / (p.radiusKm() + h);
        double offset =
                (Math.asin(air.solarDirection().y()) - Math.asin(horizon)) / Math.toRadians(.266);
        double x = Math.clamp(offset, -1, 1);
        double visibleFraction = (Math.acos(-x) + x * Math.sqrt(1 - x * x)) / Math.PI;
        sourceTransmission(
                air, y, (float) Math.max(air.solarDirection().y(), horizon + 1e-7), scratch);
        for (int c = 0; c < 3; c++) {
            scratch.sunlight[c] *= (float) (channel(air.solarIrradiance(), c) * visibleFraction);
        }
    }

    /** Transmittance for an additional distant source, e.g. the separately tracked moon. */
    public static void sourceTransmission(
            Atmosphere air, float y, float directionY, Scratch scratch) {
        var p = air.parameters();
        boolean visible =
                sampleOptical(
                        p,
                        altitude(p, y),
                        directionY,
                        scratch.quality.sunSteps(),
                        scratch.optical,
                        scratch);
        for (int c = 0; c < 3; c++) {
            scratch.sunlight[c] =
                    visible
                            ? (float)
                                    Math.exp(
                                            -extinction(
                                                    p, c, scratch.optical[0], scratch.optical[1]))
                            : 0;
        }
    }

    /** Higher step counts and single-scattering mode are independent reference controls. */
    public static void segment(
            Atmosphere air,
            float y,
            float dx,
            float dy,
            float dz,
            float distance,
            boolean disc,
            Scratch scratch) {
        var p = air.parameters();
        int viewSteps = scratch.quality.viewSteps();
        int sunSteps = scratch.quality.sunSteps();
        double h = altitude(p, y);
        double ground = boundary(p, h, dy, true);
        double length =
                Math.min(
                        Math.min(boundary(p, h, dy, false), ground),
                        distance * p.metersPerUnit() * .001);
        double cosine =
                dx * air.solarDirection().x()
                        + dy * air.solarDirection().y()
                        + dz * air.solarDirection().z();
        double rayleighPhase = 3 * (1 + cosine * cosine) / (16 * PI);
        double g = p.anisotropy();
        double aerosolPhase = (1 - g * g) / (4 * PI * Math.pow(1 + g * g - 2 * g * cosine, 1.5));
        java.util.Arrays.fill(scratch.radiance, 0);
        java.util.Arrays.fill(scratch.transmission, 1);
        for (int i = 0; i < viewSteps; i++) {
            double a = length * i * i / (viewSteps * (double) viewSteps);
            double b = length * (i + 1) * (i + 1) / (viewSteps * (double) viewSteps);
            double t = (a + b) * .5;
            double step = b - a;
            double localH = Math.max(0, height(p, h, dy, t));
            double solarMu =
                    ((p.radiusKm() + h) * air.solarDirection().y() + t * cosine)
                            / (p.radiusKm() + localH);
            double molecules = Math.exp(-localH / p.molecularHeightKm());
            double aerosols = Math.exp(-localH / p.aerosolHeightKm());
            boolean lit = sampleOptical(p, localH, solarMu, sunSteps, scratch.optical, scratch);
            // Two-stream closure: upper layer intercepts sunlight and supplies isotropic return.
            // Half of each subsequent isotropic order escapes; sum the remaining geometric series.
            double upper = Math.min(p.heightKm(), localH + p.molecularHeightKm());
            boolean upperLit =
                    scratch.quality.multiple()
                            && sampleOptical(
                                    p, upper, solarMu, sunSteps, scratch.secondary, scratch);
            for (int c = 0; c < 3; c++) {
                double molecular = channel(p.molecularScattering(), c) * molecules;
                double aerosol = channel(p.aerosolScattering(), c) * aerosols;
                double extinction = extinction(p, c, molecules, aerosols);
                double sunlight =
                        lit
                                ? Math.exp(
                                        -extinction(p, c, scratch.optical[0], scratch.optical[1]))
                                : 0;
                double source = sunlight * (molecular * rayleighPhase + aerosol * aerosolPhase);
                if (upperLit && extinction > 0) {
                    double tau =
                            extinction(
                                    p,
                                    c,
                                    molecules * p.molecularHeightKm(),
                                    aerosols * p.aerosolHeightKm());
                    double albedo = (molecular + aerosol) / extinction;
                    double intercepted = -Math.expm1(-tau);
                    double upperSun =
                            Math.exp(-extinction(p, c, scratch.secondary[0], scratch.secondary[1]));
                    source +=
                            (molecular + aerosol)
                                    * upperSun
                                    * intercepted
                                    * Math.exp(-tau * .5)
                                    * albedo
                                    / (4 * PI * (1 - .5 * albedo));
                }
                double trans = Math.exp(-extinction * step);
                double integral = integratedStep(extinction, step);
                scratch.radiance[c] +=
                        scratch.transmission[c]
                                * channel(air.solarIrradiance(), c)
                                * source
                                * integral;
                scratch.transmission[c] *= (float) trans;
            }
        }
        if (disc && ground == Double.POSITIVE_INFINITY) {
            addDisc(air, y, cosine, scratch);
        }
    }

    private static double integratedStep(double extinction, double step) {
        return extinction > 0 ? -Math.expm1(-extinction * step) / extinction : step;
    }

    private static void addDisc(Atmosphere air, float y, double cosine, Scratch scratch) {
        // Disc irradiance / projected solid angle. Only camera escape rays see this source.
        if (cosine >= Math.cos(Math.toRadians(.266))) {
            var p = air.parameters();
            double h = altitude(p, y);
            double horizon = -Math.sqrt(h * (2 * p.radiusKm() + h)) / (p.radiusKm() + h);
            sourceTransmission(
                    air, y, (float) Math.max(air.solarDirection().y(), horizon + 1e-7), scratch);
            double projected = PI * Math.pow(Math.sin(Math.toRadians(.266)), 2);
            for (int c = 0; c < 3; c++) {
                scratch.radiance[c] +=
                        scratch.sunlight[c] * channel(air.solarIrradiance(), c) / projected;
            }
        }
    }
}
