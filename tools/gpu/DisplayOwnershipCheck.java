package gpu;

import engine.RenderImage;
import engine.RenderSettings;
import engine.RenderView;

import game.GameNavigation;

import math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;

/** Owned display arrays must never alias a publication slot or another image lease. */
public final class DisplayOwnershipCheck {
    public static void main(String[] args) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(160, 96);
        var world = GpuCheck.landscape();
        var settings = RenderSettings.defaults().withPathDepth(3);
        var session = new GpuRenderSession(world, navigation.view(), settings);
        session.displayExposure(1);
        var retained = new ArrayList<int[]>();
        var expected = new ArrayList<int[]>();
        RenderImage held = null;
        int[] heldExpected = null;
        try {
            for (int frame = 0; frame < 20; frame++) {
                navigation.pose(new Vec3(frame * .04f, 2.4f, -6), .1, -.14);
                navigation.fixedResolution(frame % 2 == 0 ? 160 : 192, 96);
                session.update(world, navigation.view(), settings);
                try (var image = await(session)) {
                    var copy = new int[image.width() * image.height()];
                    image.presentationPixels().copyDisplayTo(copy, 1);
                    require(
                            image.takeDisplayPixels(2) == null,
                            "exposure mismatch must preserve cache");
                    var sibling = session.acquireImage();
                    require(
                            image.takeDisplayPixels(1) == null,
                            "concurrent leases prevent transfer");
                    sibling.close();
                    var owned = image.takeDisplayPixels(1);
                    require(
                            owned != null && Arrays.equals(copy, owned),
                            "exact owned display bytes");
                    require(image.takeDisplayPixels(1) == null, "transfer can occur only once");
                    sibling = session.acquireImage();
                    owned[0] ^= 0xffffff;
                    var other = new int[owned.length];
                    sibling.presentationPixels().copyDisplayTo(other, 1);
                    require(
                            Arrays.equals(copy, other),
                            "caller mutation cannot affect sibling lease");
                    owned[0] ^= 0xffffff;
                    require(image.samples() == 1, "transfer preserves sample count");
                    if (frame == 0) {
                        held = sibling;
                        heldExpected = copy;
                    } else {
                        sibling.close();
                    }
                    retained.add(owned);
                    expected.add(copy);
                }
                for (int i = 0; i < retained.size(); i++) {
                    require(
                            Arrays.equals(retained.get(i), expected.get(i)),
                            "slot reuse modified owned array");
                }
            }
            session.suspend();
            session.update(
                    world,
                    new RenderView(navigation.view().camera(), 160, 96),
                    settings.withRestartRevision(1));
            try (var image = await(session)) {
                require(image.takeDisplayPixels(1) != null, "resume/restart ownership");
            }
        } finally {
            session.close();
            require(session.awaitClosed(30), "GPU owner retired");
        }
        var afterClose = new int[heldExpected.length];
        held.presentationPixels().copyDisplayTo(afterClose, 1);
        require(
                Arrays.equals(afterClose, heldExpected),
                "retained raw lease survives normal close");
        held.close();
        boolean closedRejected = false;
        try {
            held.takeDisplayPixels(1);
        } catch (IllegalStateException expectedFailure) {
            closedRejected = true;
        }
        require(closedRejected, "closed lease rejects transfer");
        for (int i = 0; i < retained.size(); i++) {
            require(Arrays.equals(retained.get(i), expected.get(i)), "close modified owned array");
        }
        System.out.println(
                "PASS exact ownership transfer, sibling mutation isolation, 20 resizes, exposure"
                    + " fallback, restart, retained close");
    }

    private static RenderImage await(GpuRenderSession session) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (image.generation() == session.progress(null).requestedGeneration())
                    return image;
                image.close();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No GPU image: " + session.status());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
