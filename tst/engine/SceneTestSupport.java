package engine;

import java.security.MessageDigest;

final class SceneTestSupport {
    private SceneTestSupport() {}

    static String renderHash(SceneSnapshot snapshot, NodeId cameraId) throws Exception {
        var settings =
                RenderSettings.defaults()
                        .withWorkers(1)
                        .withSamplesPerBatch(1)
                        .withSampleTarget(1)
                        .withSeed(73)
                        .withPathDepth(2);
        try (var session =
                RenderEngine.openSession(
                        snapshot.toWorldSnapshot(),
                        snapshot.renderView(cameraId, 64, 64),
                        settings)) {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (System.nanoTime() < deadline) {
                session.request();
                var image = session.acquireImage();
                if (image != null) {
                    try (image) {
                        if (image.samples() >= 1
                                && image.generation()
                                        == session.progress(image).requestedGeneration())
                            return hash(image.rawPixels());
                    }
                }
                Thread.sleep(1);
            }
        }
        throw new AssertionError("No completed scene image");
    }

    private static String hash(RgbPixels pixels) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < pixels.height(); y++)
            for (int x = 0; x < pixels.width(); x++)
                for (int c = 0; c < 3; c++) {
                    int bits = Float.floatToIntBits(pixels.value(c, x, y));
                    digest.update((byte) (bits >>> 24));
                    digest.update((byte) (bits >>> 16));
                    digest.update((byte) (bits >>> 8));
                    digest.update((byte) bits);
                }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
