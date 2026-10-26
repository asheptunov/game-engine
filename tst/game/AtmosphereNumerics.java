package game;

import engine.Atmosphere;
import engine.AtmosphereTransport;

import math.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Physical limiting cases and converged quadrature checks, without native graphics. */
public final class AtmosphereNumerics {
    private AtmosphereNumerics() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        var output = Path.of(args.length == 0 ? "out/atmosphere" : args[0]);
        Files.createDirectories(output);
        var rows =
                new StringBuilder(
                        "preset,sunElevation,viewElevation,azimuth,altitudeWorld,channel,production,reference,converged,error,transmissionError\n");
        double[] maximum = new double[2];
        for (boolean hazy : new boolean[] {false, true}) {
            var p = hazy ? Atmosphere.Parameters.hazyMorning() : Atmosphere.Parameters.clearAir();
            for (double elevation : new double[] {70, 3, -3}) {
                var air = new Atmosphere(p, direction(elevation, 0), new Vec3(3, 3, 3));
                for (float y : new float[] {3, 150}) {
                    compareViews(air, y, hazy, elevation, rows, maximum);
                }
            }
        }
        limitingCases();
        horizonSweep(output);
        Files.writeString(output.resolve("numerics.csv"), rows);
        System.out.println(
                "PASS atmosphere numerics: max absolute radiance error="
                        + maximum[0]
                        + ", reference change="
                        + maximum[1]);
    }

    private static void compareViews(
            Atmosphere air,
            float y,
            boolean hazy,
            double elevation,
            StringBuilder rows,
            double[] maximum) {
        for (double view : new double[] {0, 10, 90, elevation}) {
            for (double azimuth : new double[] {0, 180}) {
                var d = direction(view, azimuth);
                var actual = new AtmosphereTransport.Scratch();
                var reference =
                        new AtmosphereTransport.Scratch(
                                new AtmosphereTransport.Quality(256, 192, true));
                var converged =
                        new AtmosphereTransport.Scratch(
                                new AtmosphereTransport.Quality(512, 384, true));
                AtmosphereTransport.segment(
                        air, y, d.x(), d.y(), d.z(), Float.POSITIVE_INFINITY, false, actual);
                AtmosphereTransport.segment(
                        air, y, d.x(), d.y(), d.z(), Float.POSITIVE_INFINITY, false, reference);
                AtmosphereTransport.segment(
                        air, y, d.x(), d.y(), d.z(), Float.POSITIVE_INFINITY, false, converged);
                for (int c = 0; c < 3; c++) {
                    double error = Math.abs(actual.radiance[c] - converged.radiance[c]);
                    double convergence = Math.abs(reference.radiance[c] - converged.radiance[c]);
                    maximum[0] = Math.max(maximum[0], error);
                    maximum[1] = Math.max(maximum[1], convergence);
                    rows.append(
                            String.format(
                                    Locale.ROOT,
                                    "%s,%.1f,%.1f,%.1f,%.1f,%d,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                                    hazy,
                                    elevation,
                                    view,
                                    azimuth,
                                    y,
                                    c,
                                    actual.radiance[c],
                                    reference.radiance[c],
                                    converged.radiance[c],
                                    error,
                                    Math.abs(actual.transmission[c] - converged.transmission[c])));
                    require(
                            Float.isFinite(actual.radiance[c]) && actual.radiance[c] >= 0,
                            "finite nonnegative sky");
                    require(
                            actual.transmission[c] >= 0 && actual.transmission[c] <= 1,
                            "bounded transmission");
                    require(
                            error <= .015 + .05 * converged.radiance[c],
                            "sky quadrature: "
                                    + rows.toString().lines().reduce((a, b) -> b).orElse(""));
                    require(
                            convergence <= .0005 + .01 * converged.radiance[c],
                            "reference convergence");
                    require(
                            Math.abs(actual.transmission[c] - converged.transmission[c]) <= .015,
                            "transmission quadrature");
                }
            }
        }
    }

    private static void horizonSweep(Path output) throws Exception {
        var rows = new StringBuilder("hour,solarY,skyR,skyG,skyB,sunR,sunG,sunB\n");
        var scratch = new AtmosphereTransport.Scratch();
        for (double start : new double[] {5.5, 17.5}) {
            float[] previous = null;
            for (int i = 0; i <= 4000; i++) {
                var clock = new DayNightCycle();
                clock.hour(start + i / 4000.);
                var air =
                        clock.atmosphericLighting(Atmosphere.Parameters.clearAir())
                                .sky()
                                .atmosphere();
                AtmosphereTransport.segment(
                        air,
                        3,
                        0,
                        .1f,
                        (float) Math.sqrt(.99),
                        Float.POSITIVE_INFINITY,
                        false,
                        scratch);
                AtmosphereTransport.sunlight(air, 3, scratch);
                if (previous != null) {
                    for (int c = 0; c < 3; c++) {
                        require(
                                Math.abs(previous[c] - scratch.radiance[c]) < .002,
                                "continuous twilight sky");
                        require(
                                Math.abs(previous[c + 3] - scratch.sunlight[c]) < .025,
                                "continuous finite solar disc at hour="
                                        + clock.hour()
                                        + ", channel="
                                        + c
                                        + ", previous="
                                        + previous[c + 3]
                                        + ", next="
                                        + scratch.sunlight[c]);
                    }
                }
                previous =
                        new float[] {
                            scratch.radiance[0],
                            scratch.radiance[1],
                            scratch.radiance[2],
                            scratch.sunlight[0],
                            scratch.sunlight[1],
                            scratch.sunlight[2]
                        };
                rows.append(
                        String.format(
                                Locale.ROOT,
                                "%.6f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f%n",
                                clock.hour(),
                                air.solarDirection().y(),
                                previous[0],
                                previous[1],
                                previous[2],
                                previous[3],
                                previous[4],
                                previous[5]));
            }
        }
        Files.writeString(output.resolve("horizon-sweep.csv"), rows);
    }

    static Vec3 direction(double elevation, double azimuth) {
        double e = Math.toRadians(elevation);
        double a = Math.toRadians(azimuth);
        return new Vec3(
                (float) (Math.cos(e) * Math.cos(a)),
                (float) Math.sin(e),
                (float) (Math.cos(e) * Math.sin(a)));
    }

    private static void limitingCases() {
        var p = Atmosphere.Parameters.clearAir();
        require(
                p.molecularScattering().z() > p.molecularScattering().y()
                        && p.molecularScattering().y() > p.molecularScattering().x(),
                "Rayleigh wavelength ordering");
        var vacuum = p.edited(0, 0, p.aerosolHeightKm(), Vec3.ZERO);
        var scratch = new AtmosphereTransport.Scratch();
        AtmosphereTransport.segment(
                new Atmosphere(vacuum, direction(3, 0), new Vec3(3, 3, 3)),
                3,
                1,
                0,
                0,
                100,
                false,
                scratch);
        for (int c = 0; c < 3; c++) {
            require(scratch.radiance[c] == 0 && scratch.transmission[c] == 1, "vacuum identity");
        }
        var noon = new Atmosphere(p, direction(70, 0), new Vec3(3, 3, 3));
        var low = new Atmosphere(p, direction(1, 0), new Vec3(3, 3, 3));
        AtmosphereTransport.sunlight(low, 3, scratch);
        require(
                scratch.sunlight[0] > scratch.sunlight[1]
                        && scratch.sunlight[1] > scratch.sunlight[2],
                "red sunlight at horizon");
        var sunDirection = low.solarDirection();
        var visibleDisc = new AtmosphereTransport.Scratch();
        AtmosphereTransport.segment(
                low,
                3,
                sunDirection.x(),
                sunDirection.y(),
                sunDirection.z(),
                Float.POSITIVE_INFINITY,
                true,
                visibleDisc);
        AtmosphereTransport.segment(
                low,
                3,
                sunDirection.x(),
                sunDirection.y(),
                sunDirection.z(),
                Float.POSITIVE_INFINITY,
                false,
                scratch);
        require(
                visibleDisc.radiance[0] > scratch.radiance[0] * 100,
                "solar disc follows the surface-light direction");
        AtmosphereTransport.segment(
                low,
                3,
                -sunDirection.x(),
                sunDirection.y(),
                -sunDirection.z(),
                Float.POSITIVE_INFINITY,
                true,
                visibleDisc);
        require(visibleDisc.radiance[0] < 10, "no opposite solar disc");
        var near = new AtmosphereTransport.Scratch();
        var far = new AtmosphereTransport.Scratch();
        AtmosphereTransport.segment(noon, 3, 1, 0, 0, 1, false, near);
        AtmosphereTransport.segment(noon, 3, 1, 0, 0, 100, false, far);
        require(
                far.transmission[2] < near.transmission[2] && far.radiance[2] > near.radiance[2],
                "distance haze");
        var high = new AtmosphereTransport.Scratch();
        AtmosphereTransport.segment(noon, 150, 1, 0, 0, 100, false, high);
        require(high.transmission[2] > far.transmission[2], "altitude haze");
        // Very short horizontal segments are uniform to curvature error; Beer law is analytic.
        double h = AtmosphereTransport.altitude(p, 3);
        double extinction =
                (p.molecularScattering().z() + p.absorption().z())
                                * Math.exp(-h / p.molecularHeightKm())
                        + p.aerosolScattering().z() * Math.exp(-h / p.aerosolHeightKm());
        require(
                Math.abs(near.transmission[2] - Math.exp(-extinction * .02)) < 1e-6,
                "uniform Beer limit");
        for (double hour : new double[] {5.999, 6, 6.001, 17.999, 18, 18.001, 0}) {
            var clock = new DayNightCycle();
            clock.hour(hour);
            var lighting = clock.atmosphericLighting(p);
            require(
                    lighting.sky()
                            .atmosphere()
                            .solarDirection()
                            .equals(
                                    new Atmosphere(p, clock.solarDirection(), new Vec3(3, 3, 3))
                                            .solarDirection()),
                    "signed solar clock");
            require(
                    lighting.direction().dot(lighting.sky().atmosphere().solarDirection()) < -.999,
                    "separate opposing moon");
        }
        var twilight = new Atmosphere(p, direction(-3, 0), new Vec3(3, 3, 3));
        var single =
                new AtmosphereTransport.Scratch(new AtmosphereTransport.Quality(32, 24, false));
        AtmosphereTransport.segment(twilight, 3, 1, 0, 0, Float.POSITIVE_INFINITY, false, single);
        AtmosphereTransport.segment(twilight, 3, 1, 0, 0, Float.POSITIVE_INFINITY, false, scratch);
        require(scratch.radiance[0] > single.radiance[0], "multiple-scattering twilight fill");
    }
}
