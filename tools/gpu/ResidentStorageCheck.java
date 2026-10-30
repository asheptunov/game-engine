package gpu;

import engine.*;

import game.GameNavigation;

import math.Vec3;

import java.util.*;
import java.util.concurrent.*;

/** Sharing must remove submissions while preserving sibling, raw, resized and retired images. */
public final class ResidentStorageCheck {
    public static void main(String[] args) throws Exception {
        for (boolean copied : new boolean[] {true, false}) check(copied);
        System.clearProperty("game.copyDeviceFrames");
    }

    private static void check(boolean copied) throws Exception {
        System.setProperty("game.copyDeviceFrames", Boolean.toString(copied));
        var nav = new GameNavigation();
        nav.fixedResolution(96, 64);
        var world = GpuCheck.landscape();
        var settings = RenderSettings.defaults().withPathDepth(3).withSampleTarget(1);
        var session = new GpuRenderSession(world, nav.view(), settings);
        session.displayExposure(1);
        session.devicePresentation(true);
        var ready = new Semaphore(0);
        session.onImageAvailable(ready::release);
        var retained = new ArrayList<DevicePixels>();
        var expected = new ArrayList<int[]>();
        RenderImage rawLease = null;
        try {
            for (int i = 0; i < 24; i++) {
                nav.fixedResolution(i % 2 == 0 ? 96 : 128, 64);
                nav.pose(new Vec3(i * .02f, 2.4f, -6), .1, -.14);
                session.update(world, nav.view(), settings.withRestartRevision(i));
                session.request();
                require(ready.tryAcquire(30, TimeUnit.SECONDS), "completion");
                try (var image = session.acquireImage()) {
                    var kernelField = GpuRenderSession.class.getDeclaredField("kernel");
                    kernelField.setAccessible(true);
                    var kernel = (GpuKernel) kernelField.get(session);
                    long before = session.onOwner(() -> kernel.gpu.completedSubmissions);
                    var first = (GpuDevicePixels) image.copyDevicePixels();
                    var second = (GpuDevicePixels) image.copyDevicePixels();
                    long after = session.onOwner(() -> kernel.gpu.completedSubmissions);
                    require(after - before == (copied ? 2 : 0), "copy count");
                    require(
                            (first.snapshot == second.snapshot) == !copied,
                            "sibling storage ownership");
                    var pixels = new int[image.width() * image.height()];
                    image.presentationPixels().copyDisplayTo(pixels, 1);
                    var sibling = new int[pixels.length];
                    second.copyDisplayTo(sibling, 1);
                    require(Arrays.equals(pixels, sibling), "sibling pixels");
                    second.close();
                    if (i < 5) {
                        retained.add(first);
                        expected.add(pixels);
                    } else first.close();
                    if (i == 0) rawLease = session.acquireImage();
                }
                for (int n = 0; n < retained.size(); n++) equal(retained.get(n), expected.get(n));
            }
            session.close();
            require(session.awaitClosed(30), "retirement");
            for (int n = 0; n < retained.size(); n++) equal(retained.get(n), expected.get(n));
            var raw = new int[expected.getFirst().length];
            rawLease.presentationPixels().copyDisplayTo(raw, 1);
            require(
                    Arrays.equals(raw, expected.getFirst()),
                    "raw lease after shared storage retirement");
            System.out.println(
                    "PASS "
                            + (copied ? "copied" : "shared")
                            + ": sibling/raw/device leases, 24 moving resizes, exact pixels and"
                            + " shutdown; "
                            + (copied ? 2 : 0)
                            + " copy submissions");
        } finally {
            if (rawLease != null) rawLease.close();
            for (var image : retained) image.close();
            session.close();
            require(session.awaitClosed(30), "cleanup");
        }
    }

    private static void equal(DevicePixels pixels, int[] expected) {
        var resident = (GpuDevicePixels) pixels;
        if (!resident.session.closed()) {
            resident.session.onOwner(
                    () -> {
                        // Force another GPU read: a cached CPU array would hide buffer overwrite.
                        var cache = GpuKernel.Snapshot.class.getDeclaredField("pixels");
                        cache.setAccessible(true);
                        cache.set(resident.snapshot, null);
                        return null;
                    });
        }
        var actual = new int[expected.length];
        pixels.copyDisplayTo(actual, 1);
        require(Arrays.equals(actual, expected), "retained pixels");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
