package gpu;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.util.*;

/** Exact moving-scene agreement between separate and batched TLAS submissions. */
public final class SceneSubmissionCheck {
    public static void main(String[] args) {
        var camera =
                new Camera(
                        new Vec3(0, 0, -3),
                        new Rect(new Vec3(-1, -1, -1), new Vec3(2, 0, 0), new Vec3(0, 2, 0)));
        for (var denoiser : Denoiser.values()) {
            if (denoiser == Denoiser.NRD_RELAX
                    && !java.nio.file.Files.exists(
                            java.nio.file.Path.of("out/nrd/runtime/Release/rte_nrd.dll"))) continue;
            var settings =
                    RenderSettings.defaults()
                            .withPathDepth(3)
                            .withTemporal(true, 1)
                            .withDenoiser(denoiser)
                            .withLightingScale(.5);
            var view = new RenderView(camera, 64, 64);
            var output = new RenderView(camera, 128, 128);
            var expectedRaw = new ArrayList<float[]>();
            var expectedPacked = new ArrayList<int[]>();
            var expectedSubmissions = new ArrayList<Long>();
            for (boolean separate : new boolean[] {true, false}) {
                System.setProperty("game.separateGpuSceneSubmission", Boolean.toString(separate));
                try (var kernel = new GpuKernel()) {
                    long plateau = 0;
                    for (int i = 0; i < 36; i++) {
                        var world = TlasReuseCheck.world(i);
                        var raw = new float[64 * 64 * 3];
                        var packed = new int[128 * 128];
                        long submissions = kernel.gpu.completedSubmissions;
                        long reuses = kernel.scene == null ? 0 : kernel.scene.topReuses;
                        kernel.trace(
                                world,
                                view,
                                settings,
                                0,
                                new GpuKernel.Destination(raw, packed, null, 1, null, output));
                        submissions = kernel.gpu.completedSubmissions - submissions;
                        // Packed output omits the raw download; read the actual raw estimator too.
                        var snapshot = new GpuKernel.Snapshot();
                        kernel.trace(
                                world,
                                view,
                                settings,
                                0,
                                new GpuKernel.Destination(null, null, snapshot, 1, null, output));
                        raw = snapshot.read(kernel.gpu).clone();
                        snapshot.source.close();
                        if (separate) {
                            expectedRaw.add(raw);
                            expectedPacked.add(packed);
                            expectedSubmissions.add(submissions);
                        } else {
                            if (!Arrays.equals(raw, expectedRaw.get(i))
                                    || !Arrays.equals(packed, expectedPacked.get(i)))
                                throw new AssertionError(denoiser + " image mismatch at " + i);
                            long saved = kernel.scene.topReuses > reuses ? 1 : 0;
                            if (submissions != expectedSubmissions.get(i) - saved)
                                throw new AssertionError("Submission count at " + i);
                        }
                        if (i == 24) plateau = kernel.gpu.allocatedBytes;
                        if (i > 24 && kernel.gpu.allocatedBytes != plateau)
                            throw new AssertionError("Memory did not plateau");
                    }
                }
            }
            System.out.println(
                    "PASS "
                            + denoiser
                            + ": 36 exact raw/packed frames, split visibility,"
                            + " motion/materials/order/count changes, one fewer submit per retained"
                            + " build, bounded memory");
        }
        System.clearProperty("game.separateGpuSceneSubmission");
    }
}
