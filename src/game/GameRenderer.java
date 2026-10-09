package game;

import engine.DisplayMapping;
import engine.RenderEngine;
import engine.RenderImage;
import engine.RenderSession;
import engine.RenderSettings;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One background owner for the session. Published display images are never modified. */
public final class GameRenderer implements AutoCloseable {
    public record Frame(
            BufferedImage image,
            int requestedWidth,
            int requestedHeight,
            long generation,
            long samples) {}

    private final GameNavigation navigation;
    private engine.WorldSnapshot world;
    private GameLighting lighting = GameLighting.defaults();
    private final RenderSettings settings =
            RenderSettings.defaults().withPathDepth(2).withSampleTarget(0);
    private final RenderSession session;
    private final ScheduledExecutorService executor;
    private volatile Frame frame;
    private volatile String error;
    private long lastTick = System.nanoTime();
    private long converted = -1;
    private boolean suspended;
    private boolean closed;

    public GameRenderer(GameNavigation navigation, BlockWorld blocks) {
        this.navigation = navigation;
        var gallery = blocks.snapshot();
        world =
                new engine.WorldSnapshot(
                        0,
                        gallery.instances(),
                        gallery.legacyObjects(),
                        java.util.List.of(lighting.sun()),
                        lighting.sky());
        session = RenderEngine.openSession(world, navigation.view(), settings);
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "sample-game-update");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(this::tick, 0, 16, TimeUnit.MILLISECONDS);
    }

    private synchronized void tick() {
        if (closed) {
            return;
        }
        try {
            long now = System.nanoTime();
            navigation.advance((now - lastTick) / 1e9);
            lastTick = now;
            if (!navigation.active()) {
                if (!suspended) {
                    session.suspend();
                    suspended = true;
                }
                return;
            }
            suspended = false;
            var view = navigation.view();
            session.update(world, view, settings);
            session.request();
            try (var image = session.acquireImage()) {
                if (image != null && image.publicationNanos() != converted) {
                    var display = copyImage(image);
                    frame =
                            new Frame(
                                    display,
                                    view.width(),
                                    view.height(),
                                    image.generation(),
                                    image.samples());
                    converted = image.publicationNanos();
                }
            }
        } catch (RuntimeException failure) {
            error = "Rendering failed: " + failure.getMessage();
            navigation.active(false);
            session.suspend();
            suspended = true;
        }
    }

    /** Convert under the lease, flipping the engine's bottom-up rows to AWT's top-down rows. */
    public static BufferedImage copyImage(RenderImage image) {
        var result = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_RGB);
        var pixels = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        var source = image.presentationPixels();
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int red = Byte.toUnsignedInt(DisplayMapping.encode(source.value(0, x, y), 1));
                int green = Byte.toUnsignedInt(DisplayMapping.encode(source.value(1, x, y), 1));
                int blue = Byte.toUnsignedInt(DisplayMapping.encode(source.value(2, x, y), 1));
                pixels[(image.height() - 1 - y) * image.width() + x] =
                        (red << 16) | (green << 8) | blue;
            }
        }
        return result;
    }

    public Frame frame() {
        return frame;
    }

    public synchronized GameLighting lighting() {
        return lighting;
    }

    /** Replace immutable input once per effective edit; reset/undo must not revive old images. */
    public synchronized void lighting(GameLighting value) {
        if (!lighting.equals(value)) {
            lighting = value;
            world =
                    new engine.WorldSnapshot(
                            world.revision() + 1,
                            world.instances(),
                            world.legacyObjects(),
                            java.util.List.of(value.sun()),
                            value.sky());
        }
    }

    public String error() {
        return error;
    }

    public synchronized boolean suspended() {
        return suspended;
    }

    public synchronized boolean closed() {
        return closed && session.closed() && executor.isShutdown();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            navigation.active(false);
            executor.shutdownNow();
            session.close();
        }
    }
}
