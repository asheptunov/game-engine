package game;

import engine.*;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Exact display conversion and paired timing, using real CPU/GPU image leases without a window. */
public final class DisplayCopyCheck {
    private DisplayCopyCheck() {}

    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        numericEdges();
        String backend = args.length == 0 ? "gpu" : args[0];
        int width = args.length < 2 ? 1600 : Integer.parseInt(args[1]);
        int height = width * 5 / 8 + 1; // Include an odd dimension and non-square row orientation.
        var navigation = new GameNavigation();
        navigation.fixedResolution(width, height);
        var source = BlockWorld.landscape().snapshot();
        var light = GameLighting.defaults();
        var world =
                new WorldSnapshot(
                        0,
                        source.instances(),
                        source.legacyObjects(),
                        java.util.List.of(light.sun()),
                        light.sky());
        var settings =
                RenderSettings.defaults()
                        .withPathDepth(0)
                        .withSampleTarget(1)
                        .withInteractive(false, 16.67, 0, 0);
        var ready = new Semaphore(0);
        try (var session = RenderEngine.openSession(world, navigation.view(), settings, backend)) {
            session.onImageAvailable(ready::release);
            session.request();
            require(ready.tryAcquire(60, TimeUnit.SECONDS), "image publication");
            var image = session.acquireImage();
            require(image != null, "image lease");
            try (image) {
                for (float exposure : new float[] {0, .125f, 1, 4, 100}) {
                    var expected = pixels(reference(image, exposure));
                    var actual = pixels(GameRenderer.copyImage(image, exposure));
                    require(Arrays.equals(expected, actual), "exact RGB exposure=" + exposure);
                }
                boolean rejected = false;
                try {
                    image.presentationPixels().copyDisplayTo(new int[1], 1);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                require(rejected, "invalid destination rejected");
                var original = pixels(GameRenderer.copyImage(image, 1)).clone();
                session.update(world, navigation.view(), settings.withRestartRevision(1));
                session.request();
                require(ready.tryAcquire(60, TimeUnit.SECONDS), "replacement publication");
                require(
                        Arrays.equals(original, pixels(GameRenderer.copyImage(image, 1))),
                        "held image remains stable after replacement");
                Thread.currentThread().interrupt();
                var interruptedCopy = pixels(GameRenderer.copyImage(image, 1));
                require(Thread.interrupted(), "interrupted conversion restores interrupt flag");
                require(
                        Arrays.equals(original, interruptedCopy),
                        "interrupted conversion finishes all rows");
                for (int i = 0; i < 40; i++) {
                    consume(reference(image, 1));
                    consume(GameRenderer.copyImage(image, 1));
                }
                System.out.println("backend,width,height,round,referenceMedianMs,bulkMedianMs");
                for (int round = 0; round < 3; round++) {
                    double[] old = new double[40], bulk = new double[40];
                    for (int i = 0; i < old.length; i++) {
                        if ((i + round) % 2 == 0) {
                            old[i] = timed(image, false);
                            bulk[i] = timed(image, true);
                        } else {
                            bulk[i] = timed(image, true);
                            old[i] = timed(image, false);
                        }
                    }
                    Arrays.sort(old);
                    Arrays.sort(bulk);
                    System.out.printf(
                            Locale.ROOT,
                            "%s,%d,%d,%d,%.4f,%.4f%n",
                            backend,
                            image.width(),
                            image.height(),
                            round,
                            old[20],
                            bulk[20]);
                }
            }
            boolean rejected = false;
            try {
                image.presentationPixels().copyDisplayTo(new int[width * height], 1);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            require(rejected, "closed lease rejected");
        }
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }

    private static void numericEdges() {
        float[] source = {
            Float.NaN,
            -1,
            Float.POSITIVE_INFINITY,
            0,
            .18f,
            1,
            Float.MAX_VALUE,
            Float.MIN_VALUE,
            100,
            .0031308f,
            .5f,
            2
        };
        int[] output = new int[4];
        for (float exposure : new float[] {0, .125f, 1, 100}) {
            DisplayCopy.interleaved(source, output, 2, 2, exposure);
            for (int pixel = 0; pixel < 4; pixel++) {
                int offset = pixel * 3;
                int expected =
                        (Byte.toUnsignedInt(DisplayMapping.encode(source[offset], exposure)) << 16)
                                | (Byte.toUnsignedInt(
                                                DisplayMapping.encode(source[offset + 1], exposure))
                                        << 8)
                                | Byte.toUnsignedInt(
                                        DisplayMapping.encode(source[offset + 2], exposure));
                require(
                        output[(1 - pixel / 2) * 2 + pixel % 2] == expected,
                        "numeric edge and orientation " + pixel + " exposure=" + exposure);
            }
        }
    }

    private static int[] pixels(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private static void consume(BufferedImage image) {
        int[] values = pixels(image);
        sink = values[values.length / 2];
    }

    private static double timed(RenderImage image, boolean bulk) {
        long start = System.nanoTime();
        consume(bulk ? GameRenderer.copyImage(image, 1) : reference(image, 1));
        return (System.nanoTime() - start) / 1e6;
    }

    /** Previous game conversion retained independently as the byte-for-byte reference. */
    static BufferedImage reference(RenderImage image, float exposure) {
        var result = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_RGB);
        var output = pixels(result);
        var source = image.presentationPixels();
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int red =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(0, x, y), exposure));
                int green =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(1, x, y), exposure));
                int blue =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(2, x, y), exposure));
                output[(image.height() - 1 - y) * image.width() + x] =
                        (red << 16) | (green << 8) | blue;
            }
        }
        return result;
    }
}
