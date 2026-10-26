package engine;

import math.Vec3;

import java.util.List;

/**
 * CPU oracle and shared layout policy for the GPU sunlight-transmission table. Production CPU
 * tracing retains direct quadrature; constructing this table there costs more than it saves.
 * Geometry shadows remain exact ray queries on both backends.
 */
public final class FogSunTable {
    public static final int SIZE = 49;
    public static final int CELLS = SIZE * SIZE * SIZE;

    /** World-space bounds, fog values and exact normalized solar direction form the key. */
    public record Layout(List<FogRegion> regions, Vec3 direction, Vec3 lower, Vec3 extent) {}

    private final Layout layout;
    private final float[] values = new float[CELLS * 3];
    private final float[] cosines;
    private final float[] sines;

    public static Layout layout(Sky sky) {
        if (Boolean.getBoolean("engine.fogSunReference")
                || sky.atmosphere() == null
                || sky.atmosphere().solarDirection().y() <= .05f
                || sky.fog().isEmpty()
                || sky.fog().size() > 2) {
            return null;
        }
        // A bounded optimization domain, not a restriction on supported fog inputs.
        // Sharp, dense or finely patterned media retain the reference integrator.
        if (sky.fog().stream().anyMatch(r -> !smooth(r))) {
            return null;
        }
        float[] low = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] high = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var region : sky.fog()) {
            expand(region, low, high);
        }
        var extent = new Vec3(high[0] - low[0], high[1] - low[1], high[2] - low[2]);
        for (var region : sky.fog()) {
            if (!resolved(region, extent)) {
                return null;
            }
        }
        return new Layout(
                sky.fog(),
                sky.atmosphere().solarDirection(),
                new Vec3(low[0], low[1], low[2]),
                extent);
    }

    private static boolean smooth(FogRegion region) {
        float largest =
                Math.max(
                        region.halfExtent().x(),
                        Math.max(region.halfExtent().y(), region.halfExtent().z()));
        for (int channel = 0; channel < 3; channel++) {
            float extinction =
                    region.density()
                            * (FogRegion.channel(region.scattering(), channel)
                                    + FogRegion.channel(region.absorption(), channel));
            if (extinction * largest > 1) {
                return false;
            }
        }
        return region.feather() >= .2f && region.frequency() <= 2 && region.heightFalloff() <= 2;
    }

    private static void expand(FogRegion region, float[] low, float[] high) {
        double angle = Math.toRadians(region.yawDegrees());
        float cosine = (float) Math.abs(Math.cos(angle));
        float sine = (float) Math.abs(Math.sin(angle));
        float[] span = {
            cosine * region.halfExtent().x() + sine * region.halfExtent().z(),
            region.halfExtent().y(),
            sine * region.halfExtent().x() + cosine * region.halfExtent().z()
        };
        for (int axis = 0; axis < 3; axis++) {
            low[axis] = Math.min(low[axis], FogRegion.channel(region.center(), axis) - span[axis]);
            high[axis] =
                    Math.max(high[axis], FogRegion.channel(region.center(), axis) + span[axis]);
        }
    }

    private static boolean resolved(FogRegion region, Vec3 extent) {
        double angle = Math.toRadians(region.yawDegrees());
        double cosine = Math.abs(Math.cos(angle));
        double sine = Math.abs(Math.sin(angle));
        // At least twelve cells per local half extent; widely separated or tiny regions use
        // quadrature.
        return (cosine * extent.x() + sine * extent.z()) / (SIZE - 1)
                        <= region.halfExtent().x() / 12
                && extent.y() / (SIZE - 1) <= region.halfExtent().y() / 12
                && (sine * extent.x() + cosine * extent.z()) / (SIZE - 1)
                        <= region.halfExtent().z() / 12;
    }

    public FogSunTable(Layout layout) {
        this.layout = java.util.Objects.requireNonNull(layout);
        cosines = new float[layout.regions().size()];
        sines = new float[cosines.length];
        for (int index = 0; index < cosines.length; index++) {
            double angle = Math.toRadians(layout.regions().get(index).yawDegrees());
            cosines[index] = (float) Math.cos(angle);
            sines[index] = (float) Math.sin(angle);
        }
        var scratch = new FogTransport.Scratch();
        var direction = layout.direction();
        var lower = layout.lower();
        var extent = layout.extent();
        for (int index = 0; index < CELLS; index++) {
            int x = index % SIZE;
            int y = (index / SIZE) % SIZE;
            int z = index / (SIZE * SIZE);
            FogTransport.transmission(
                    layout.regions(),
                    scratch.ray.set(
                            lower.x() + extent.x() * x / (SIZE - 1),
                            lower.y() + extent.y() * y / (SIZE - 1),
                            lower.z() + extent.z() * z / (SIZE - 1),
                            direction.x(),
                            direction.y(),
                            direction.z()),
                    scratch);
            System.arraycopy(scratch.sun, 0, values, index * 3, 3);
        }
    }

    public Layout layout() {
        return layout;
    }

    private static boolean edgeBand(float coordinate, float edge) {
        return Math.abs(coordinate) > edge && Math.abs(coordinate) < 1.1f;
    }

    private boolean nearEdge(FogTransport.Ray ray) {
        for (int index = 0; index < cosines.length; index++) {
            var region = layout.regions().get(index);
            float px = ray.x - region.center().x();
            float pz = ray.z - region.center().z();
            float u = (cosines[index] * px - sines[index] * pz) / region.halfExtent().x();
            float v = (ray.y - region.center().y()) / region.halfExtent().y();
            float w = (sines[index] * px + cosines[index] * pz) / region.halfExtent().z();
            float edge = 1 - region.feather();
            // Rays grazing a box face integrate long columns through its narrow feather layer.
            // Keep direct quadrature there rather than interpolate steep boundary curvature.
            var direction = layout.direction();
            float du = cosines[index] * direction.x() - sines[index] * direction.z();
            float dw = sines[index] * direction.x() + cosines[index] * direction.z();
            if ((Math.abs(du) < .3f && edgeBand(u, edge))
                    || (Math.abs(direction.y()) < .3f && edgeBand(v, edge))
                    || (Math.abs(dw) < .3f && edgeBand(w, edge))) {
                return true;
            }
        }
        return false;
    }

    /** False outside the table, near edge bands, or for other lights: integrate directly. */
    public boolean sample(FogTransport.Ray ray, float[] result) {
        var direction = layout.direction();
        if (ray.dx != direction.x()
                || ray.dy != direction.y()
                || ray.dz != direction.z()
                || nearEdge(ray)) {
            return false;
        }
        float x = (ray.x - layout.lower().x()) / layout.extent().x() * (SIZE - 1);
        float y = (ray.y - layout.lower().y()) / layout.extent().y() * (SIZE - 1);
        float z = (ray.z - layout.lower().z()) / layout.extent().z() * (SIZE - 1);
        if (x < 0 || y < 0 || z < 0 || x > SIZE - 1 || y > SIZE - 1 || z > SIZE - 1) {
            return false;
        }
        int ix = Math.min((int) x, SIZE - 2);
        int iy = Math.min((int) y, SIZE - 2);
        int iz = Math.min((int) z, SIZE - 2);
        int base = ((iz * SIZE + iy) * SIZE + ix) * 3;
        for (int channel = 0; channel < 3; channel++) {
            float lower = plane(base + channel, x - ix, y - iy);
            float upper = plane(base + channel + SIZE * SIZE * 3, x - ix, y - iy);
            result[channel] = lower + (upper - lower) * (z - iz);
        }
        return true;
    }

    private float plane(int base, float x, float y) {
        float lower = values[base] + (values[base + 3] - values[base]) * x;
        int upper = base + SIZE * 3;
        float high = values[upper] + (values[upper + 3] - values[upper]) * x;
        return lower + (high - lower) * y;
    }
}
