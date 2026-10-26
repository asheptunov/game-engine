package engine;

import engine.lights.DirectionalLight;

import java.util.List;

/** Bounded deterministic single scattering. All local integration distances use world units. */
public final class FogTransport {
    public static final int VIEW_STEPS = 24;
    public static final int SUN_STEPS = 24;
    public static final int MAX_REGIONS = 8;

    private FogTransport() {}

    /**
     * Mutable worker-local ray, reused without transport allocations. Direction must be unit
     * length.
     */
    public static final class Ray {
        float x;
        float y;
        float z;
        float dx;
        float dy;
        float dz;

        public Ray set(float x, float y, float z, float dx, float dy, float dz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            return this;
        }
    }

    @FunctionalInterface
    public interface Visibility {
        boolean visible(float x, float y, float z, float dx, float dy, float dz);
    }

    public static final class Scratch {
        public final float[] radiance = new float[3];
        public final float[] transmission = new float[3];
        public final float[] sun = new float[3];
        public final Ray ray = new Ray();
        private final Ray incident = new Ray();
        private final double[] ends = new double[2 * MAX_REGIONS];
        private final double[] interval = new double[2];
        private final double[] extinction = new double[3];
        private final double[] source = new double[3];
        private final AtmosphereTransport.Scratch air = new AtmosphereTransport.Scratch();
        private final AtmosphereTransport.Scratch localAir =
                new AtmosphereTransport.Scratch(new AtmosphereTransport.Quality(1, 0, true));
        private final int viewSteps;
        private final int sunSteps;

        public Scratch() {
            this(VIEW_STEPS, SUN_STEPS);
        }

        public Scratch(int viewSteps, int sunSteps) {
            if (viewSteps < 1 || sunSteps < 1) {
                throw new IllegalArgumentException("Fog integration steps must be positive");
            }
            this.viewSteps = viewSteps;
            this.sunSteps = sunSteps;
        }
    }

    public static void transmission(List<FogRegion> regions, Ray ray, Scratch s) {
        java.util.Arrays.fill(s.sun, 1);
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            var region = regions.get(regionIndex);
            if (!region.active() || !interval(region, ray, Float.POSITIVE_INFINITY, s.interval)) {
                continue;
            }
            double step = (s.interval[1] - s.interval[0]) / s.sunSteps;
            double column = 0;
            for (int i = 0; i < s.sunSteps; i++) {
                column += density(region, ray, (float) (s.interval[0] + (i + .5) * step)) * step;
            }
            for (int c = 0; c < 3; c++) {
                s.sun[c] *=
                        (float)
                                Math.exp(
                                        -column
                                                * (FogRegion.channel(region.scattering(), c)
                                                        + FogRegion.channel(
                                                                region.absorption(), c)));
            }
        }
    }

    private static boolean interval(FogRegion region, Ray ray, float limit, double[] result) {
        return region.interval(ray.x, ray.y, ray.z, ray.dx, ray.dy, ray.dz, limit, result);
    }

    private static float density(FogRegion region, Ray ray, float t) {
        return region.sample(ray.x + ray.dx * t, ray.y + ray.dy * t, ray.z + ray.dz * t);
    }

    public static void segment(
            Sky sky,
            DirectionalLight[] lights,
            Ray ray,
            float distance,
            boolean disc,
            Visibility visibility,
            Scratch s) {
        java.util.Arrays.fill(s.radiance, 0);
        java.util.Arrays.fill(s.transmission, 1);
        int count = boundaries(sky.fog(), ray, distance, s);
        double previous = 0;
        for (int index = 0; index < count; index++) {
            double end = s.ends[index];
            if (end <= previous) {
                continue;
            }
            if (inside(sky.fog(), ray, (previous + end) * .5, s)) {
                integrate(sky, lights, ray, previous, end, visibility, s);
            } else {
                air(sky.atmosphere(), ray, previous, end - previous, false, s);
            }
            previous = end;
        }
        air(sky.atmosphere(), ray, previous, distance - previous, disc, s);
    }

    private static int boundaries(List<FogRegion> regions, Ray ray, float distance, Scratch s) {
        int count = 0;
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            var region = regions.get(regionIndex);
            if (region.active() && interval(region, ray, distance, s.interval)) {
                s.ends[count++] = s.interval[0];
                s.ends[count++] = s.interval[1];
            }
        }
        java.util.Arrays.sort(s.ends, 0, count);
        return count;
    }

    private static boolean inside(List<FogRegion> regions, Ray ray, double middle, Scratch s) {
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            var region = regions.get(regionIndex);
            if (region.active()
                    && interval(region, ray, Float.POSITIVE_INFINITY, s.interval)
                    && middle > s.interval[0]
                    && middle < s.interval[1]) {
                return true;
            }
        }
        return false;
    }

    private static void air(
            Atmosphere air, Ray ray, double start, double distance, boolean disc, Scratch s) {
        if (air == null) {
            return;
        }
        AtmosphereTransport.segment(
                air,
                ray.y + ray.dy * (float) start,
                ray.dx,
                ray.dy,
                ray.dz,
                (float) distance,
                disc,
                s.air);
        for (int c = 0; c < 3; c++) {
            s.radiance[c] += s.transmission[c] * s.air.radiance[c];
            s.transmission[c] *= s.air.transmission[c];
        }
    }

    private static void integrate(
            Sky sky,
            DirectionalLight[] lights,
            Ray ray,
            double start,
            double end,
            Visibility visibility,
            Scratch s) {
        double step = (end - start) / s.viewSteps;
        for (int i = 0; i < s.viewSteps; i++) {
            float t = (float) (start + (i + .5) * step);
            var point =
                    s.incident.set(
                            ray.x + ray.dx * t, ray.y + ray.dy * t, ray.z + ray.dz * t, 0, 0, 0);
            localCoefficients(sky, ray, point, step, s);
            illuminate(sky, lights, ray, point, visibility, s);
            for (int c = 0; c < 3; c++) {
                s.radiance[c] += s.transmission[c] * s.source[c] * integral(s.extinction[c], step);
                s.transmission[c] *= (float) Math.exp(-s.extinction[c] * step);
            }
        }
    }

    private static void localCoefficients(Sky sky, Ray view, Ray point, double step, Scratch s) {
        java.util.Arrays.fill(s.extinction, 0);
        java.util.Arrays.fill(s.source, 0);
        if (sky.atmosphere() != null) {
            AtmosphereTransport.segment(
                    sky.atmosphere(),
                    point.y - view.dy * (float) (step * .5),
                    view.dx,
                    view.dy,
                    view.dz,
                    (float) step,
                    false,
                    s.localAir);
            for (int c = 0; c < 3; c++) {
                double k = -Math.log(Math.max(1e-30, s.localAir.transmission[c])) / step;
                s.extinction[c] = k;
                s.source[c] = s.localAir.radiance[c] / integral(k, step);
            }
        }
        for (int regionIndex = 0; regionIndex < sky.fog().size(); regionIndex++) {
            var region = sky.fog().get(regionIndex);
            float density = region.sample(point.x, point.y, point.z);
            for (int c = 0; c < 3; c++) {
                s.extinction[c] +=
                        density
                                * (FogRegion.channel(region.scattering(), c)
                                        + FogRegion.channel(region.absorption(), c));
            }
        }
    }

    private static void illuminate(
            Sky sky,
            DirectionalLight[] lights,
            Ray view,
            Ray point,
            Visibility visibility,
            Scratch s) {
        var air = sky.atmosphere();
        if (air != null) {
            AtmosphereTransport.sunlight(air, point.y, s.localAir);
            direction(point, air.solarDirection());
            scatter(sky, view, point, s.localAir.sunlight, visibility, s);
        }
        for (var light : lights) {
            direction(point, light.direction());
            if (air != null) {
                AtmosphereTransport.sourceTransmission(air, point.y, point.dy, s.localAir);
            } else {
                java.util.Arrays.fill(s.localAir.sunlight, 1);
            }
            for (int c = 0; c < 3; c++) {
                s.localAir.sunlight[c] *= light.strength() * FogRegion.channel(light.color(), c);
            }
            scatter(sky, view, point, s.localAir.sunlight, visibility, s);
        }
    }

    private static void direction(Ray ray, math.Vec3 direction) {
        ray.dx = direction.x();
        ray.dy = direction.y();
        ray.dz = direction.z();
    }

    private static void scatter(
            Sky sky, Ray view, Ray incident, float[] irradiance, Visibility visibility, Scratch s) {
        if (irradiance[0] + irradiance[1] + irradiance[2] == 0
                || !visibility.visible(
                        incident.x,
                        incident.y,
                        incident.z,
                        incident.dx,
                        incident.dy,
                        incident.dz)) {
            return;
        }
        transmission(sky.fog(), incident, s);
        double cosine = view.dx * incident.dx + view.dy * incident.dy + view.dz * incident.dz;
        for (int regionIndex = 0; regionIndex < sky.fog().size(); regionIndex++) {
            var region = sky.fog().get(regionIndex);
            double g = region.anisotropy();
            double phase = (1 - g * g) / (4 * Math.PI * Math.pow(1 + g * g - 2 * g * cosine, 1.5));
            double density = region.sample(incident.x, incident.y, incident.z);
            for (int c = 0; c < 3; c++) {
                s.source[c] +=
                        density
                                * FogRegion.channel(region.scattering(), c)
                                * phase
                                * irradiance[c]
                                * s.sun[c];
            }
        }
    }

    private static double integral(double k, double step) {
        return k > 0 ? -Math.expm1(-k * step) / k : step;
    }
}
