package engine;

import engine.lights.DirectionalLight;

import math.Vec3;

/** Immutable optical inputs. Distances in this model are kilometres, except the world mapping. */
public record Atmosphere(Parameters parameters, Vec3 solarDirection, Vec3 solarIrradiance) {
    public record Parameters(
            float metersPerUnit,
            float groundY,
            float radiusKm,
            float heightKm,
            float molecularHeightKm,
            float aerosolHeightKm,
            Vec3 molecularScattering,
            Vec3 aerosolScattering,
            Vec3 absorption,
            float anisotropy) {
        public Parameters {
            range(metersPerUnit, .1f, 1000, "metres per world unit");
            range(groundY, -10000, 10000, "ground reference Y");
            range(radiusKm, 1000, 10000, "planet radius (km)");
            range(heightKm, 20, 200, "atmosphere height (km)");
            range(molecularHeightKm, 1, 20, "molecular scale height (km)");
            range(aerosolHeightKm, .1f, 5, "aerosol scale height (km)");
            range(anisotropy, -.9f, .9f, "aerosol anisotropy");
            coefficients(molecularScattering);
            coefficients(aerosolScattering);
            coefficients(absorption);
        }

        public static Parameters clearAir() {
            return new Parameters(
                    20,
                    -2,
                    6371,
                    80,
                    8,
                    1.2f,
                    new Vec3(.0058f, .0135f, .0331f),
                    new Vec3(.004f, .004f, .004f),
                    new Vec3(.0003f, .0006f, .0001f),
                    .76f);
        }

        public Parameters edited(
                float molecules, float aerosols, float falloffKm, Vec3 absorption) {
            range(molecules, 0, 4, "molecular density scale");
            range(aerosols, 0, 10, "aerosol density scale");
            return new Parameters(
                    metersPerUnit,
                    groundY,
                    radiusKm,
                    heightKm,
                    molecularHeightKm,
                    falloffKm,
                    new Vec3(.0058f, .0135f, .0331f).scale(molecules),
                    new Vec3(.004f, .004f, .004f).scale(aerosols),
                    absorption,
                    anisotropy);
        }

        public static Parameters hazyMorning() {
            return clearAir().edited(1, 8, .35f, clearAir().absorption());
        }

        private static void coefficients(Vec3 value) {
            if (value == null) {
                throw new IllegalArgumentException("Atmospheric coefficients required");
            }
            range(value.x(), 0, .2f, "coefficient R (1/km)");
            range(value.y(), 0, .2f, "coefficient G (1/km)");
            range(value.z(), 0, .2f, "coefficient B (1/km)");
        }
    }

    public Atmosphere {
        java.util.Objects.requireNonNull(parameters);
        solarDirection = new DirectionalLight(solarDirection, new Vec3(1, 1, 1), 0).direction();
        if (solarIrradiance == null) {
            throw new IllegalArgumentException("Solar irradiance required");
        }
        range(solarIrradiance.x(), 0, 100, "solar irradiance R");
        range(solarIrradiance.y(), 0, 100, "solar irradiance G");
        range(solarIrradiance.z(), 0, 100, "solar irradiance B");
    }

    private static void range(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        }
    }
}
