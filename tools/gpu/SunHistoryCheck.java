package gpu;

import engine.*;

import game.DayNightCycle;

import java.util.*;

/** Real-device checks for continuous sunlight, abrupt edits and deterministic samples. */
public final class SunHistoryCheck {
    private static final RenderView VIEW =
            new RenderView(
                    new Camera(
                            new math.Vec3(0, 1, -5),
                            new engine.objects.Rect(
                                    new math.Vec3(-1, 0, -3),
                                    new math.Vec3(2, 0, 0),
                                    new math.Vec3(0, 2, 0))),
                    96,
                    64);

    static WorldSnapshot world(double seconds, long epoch, boolean tagged) {
        var base = ReconstructionCheck.world(0, false, false);
        var clock = new DayNightCycle();
        clock.hour(9 + seconds * 24 / 600);
        var light = clock.atmosphericLighting(Atmosphere.Parameters.clearAir());
        return new WorldSnapshot(
                0,
                base.instances(),
                List.of(),
                List.of(light.sun()),
                light.sky(),
                tagged ? new LightingContinuity(1, epoch, seconds) : LightingContinuity.NONE);
    }

    public static void main(String[] args) throws Exception {
        var first = world(0, 0, true);
        require(
                GpuLightingHistory.confidence(first, world(.02, 0, true)) > 0,
                "continuous sun admitted");
        require(
                GpuLightingHistory.confidence(world(0, 0, false), world(.02, 0, false)) == 0,
                "untagged edits remain strict");
        require(
                GpuLightingHistory.confidence(first, world(.02, 1, true)) == 0,
                "manual jump epoch rejects even tiny edits");
        require(
                GpuLightingHistory.confidence(first, world(.6, 0, true)) == 0,
                "stale simulation history rejects");
        var moved = world(.02, 0, true);
        var noon = new DayNightCycle();
        noon.hour(12);
        var light = noon.atmosphericLighting(Atmosphere.Parameters.clearAir());
        var jump =
                new WorldSnapshot(
                        0,
                        first.instances(),
                        List.of(),
                        List.of(light.sun()),
                        light.sky(),
                        moved.lightingContinuity());
        require(
                GpuLightingHistory.confidence(first, jump) == 0,
                "large direction change rejects despite a continuity tag");
        var haze = noon.atmosphericLighting(Atmosphere.Parameters.hazyMorning());
        var edited =
                new WorldSnapshot(
                        0,
                        first.instances(),
                        List.of(),
                        List.of(haze.sun()),
                        haze.sky(),
                        moved.lightingContinuity());
        require(
                GpuLightingHistory.confidence(first, edited) == 0,
                "atmospheric parameter edits reject");
        for (var denoiser : Denoiser.values()) {
            if (denoiser == Denoiser.NRD_RELAX && !GpuNrdLibrary.available()) continue;
            check(denoiser);
        }
        System.out.println("Sun history checks passed.");
    }

    private static void check(Denoiser denoiser) {
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(4)
                        .withTemporal(true, 1)
                        .withDenoiser(denoiser);
        var raw = new float[96 * 64 * 3];
        var shown = new GpuKernel.Snapshot();
        try (var kernel = new GpuKernel()) {
            trace(kernel, world(0, 0, true), settings, raw, shown);
            kernel.temporal.reset();
            trace(kernel, world(0, 0, true), settings, raw, shown);
            var initial = raw.clone();
            trace(kernel, world(.02, 0, true), settings, raw, shown);
            require(
                    kernel.temporal.motion.mapped.getFloat(128 + 3 * 16 + 4) == 1,
                    denoiser + " keeps geometry correspondence across sun motion");
            require(
                    kernel.temporal.lightingConfidence > 0
                            && kernel.temporal.lightingConfidence < 1,
                    denoiser + " reduces history inertia while lighting changes");
            if (denoiser == Denoiser.NRD_RELAX)
                require(
                        kernel.temporal.nrd.frameIndex == 2,
                        "NRD does not restart for a gradual change");
            var filtered = shown.read(kernel.gpu).clone();
            var expectedRaw = raw.clone();
            kernel.temporal.reset();
            trace(kernel, world(.02, 0, true), settings, raw, shown);
            require(
                    Arrays.equals(expectedRaw, raw),
                    denoiser + " history never changes raw lighting samples");
            require(
                    !Arrays.equals(filtered, shown.read(kernel.gpu)),
                    denoiser + " gradual lighting actually reuses history");
            trace(kernel, world(.04, 1, true), settings, raw, shown);
            var reset = shown.read(kernel.gpu).clone();
            kernel.temporal.reset();
            trace(kernel, world(.04, 1, true), settings, raw, shown);
            require(
                    Arrays.equals(reset, shown.read(kernel.gpu)),
                    denoiser + " manual jump produces fresh history");
            trace(kernel, world(0, 0, true), settings, raw, shown);
            require(
                    Arrays.equals(initial, raw),
                    denoiser + " captured simulation samples replay exactly");
            var before = world(0, 0, true);
            var alternateId =
                    new WorldSnapshot(
                            0,
                            before.instances(),
                            before.legacyObjects(),
                            before.lights(),
                            before.sky(),
                            new LightingContinuity(100, 8, 0));
            trace(kernel, alternateId, settings, raw, shown);
            require(
                    Arrays.equals(initial, raw),
                    denoiser + " random stream does not depend on process timeline IDs");
            for (float value : raw)
                if (!Float.isFinite(value) || value < 0)
                    throw new AssertionError("Invalid radiance");
            shown.source.close();
        }
    }

    private static void trace(
            GpuKernel kernel,
            WorldSnapshot world,
            RenderSettings settings,
            float[] raw,
            GpuKernel.Snapshot shown) {
        kernel.trace(
                world, VIEW, settings, 0, new GpuKernel.Destination(raw, null, null, 1, shown));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
}
