package game;

import engine.DisplayMapping;
import engine.RenderEngine;
import engine.RenderImage;
import engine.RenderSession;
import engine.RenderSettings;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One background owner for the session. Published display images are never modified. */
public final class GameRenderer implements AutoCloseable {
    public record Frame(
            BufferedImage image,
            int requestedWidth,
            int requestedHeight,
            long generation,
            long samples,
            long publicationNanos,
            long capturedNanos,
            engine.Camera camera,
            Metrics metrics) {}

    /** Benchmark-only timings at copy completion; trace CPU sums all active tracing threads. */
    public record Metrics(
            long traceNanos,
            long copyNanos,
            long ageNanos,
            long pickupNanos,
            profiling.TraceProfile.Stats trace) {}

    private final GameNavigation navigation;
    private final String backend;
    private engine.WorldSnapshot world;
    private GameLighting lighting = GameLighting.defaults();
    private final DayNightCycle cycle = new DayNightCycle();
    private long lastLightingUpdate;
    private RenderSettings settings =
            RenderSettings.defaults()
                    .withPathDepth(2)
                    .withSampleTarget(0)
                    .withTileSize(16)
                    .withInteractive(true, 1000. / 60, 0, 0);
    private final RenderSession session;
    private final GameAutofocus autofocus;
    private final java.util.function.BiFunction<RenderImage, Float, BufferedImage> imageConverter;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean completionQueued = new AtomicBoolean();
    private volatile Frame frame;
    private volatile String error;
    private long lastTick = System.nanoTime();
    private long converted = -1;
    private float exposure = 1;
    private long refinementTarget;
    private java.util.function.Consumer<Frame> publicationObserver;
    private boolean suspended;
    private boolean closed;

    public GameRenderer(GameNavigation navigation, BlockWorld blocks) {
        this(navigation, blocks, "cpu");
    }

    public GameRenderer(GameNavigation navigation, BlockWorld blocks, String backend) {
        this(navigation, blocks, backend, GameRenderer::copyImage);
    }

    GameRenderer(
            GameNavigation navigation,
            BlockWorld blocks,
            String backend,
            java.util.function.BiFunction<RenderImage, Float, BufferedImage> imageConverter) {
        this(navigation, blocks.snapshot(), backend, imageConverter);
    }

    public GameRenderer(GameNavigation navigation, engine.WorldSnapshot source, String backend) {
        this(navigation, source, backend, GameRenderer::copyImage);
    }

    private GameRenderer(
            GameNavigation navigation,
            engine.WorldSnapshot source,
            String backend,
            java.util.function.BiFunction<RenderImage, Float, BufferedImage> imageConverter) {
        this.imageConverter = java.util.Objects.requireNonNull(imageConverter);
        this.navigation = navigation;
        this.backend = backend;
        if (backend.equals("gpu") || navigation.fixedResolution()) {
            settings = settings.withInteractive(false, settings.interactiveMillis(), 0, 0);
        }
        var prepared = source;
        world =
                new engine.WorldSnapshot(
                        0,
                        prepared.instances(),
                        prepared.legacyObjects(),
                        java.util.List.of(lighting.sun()),
                        lighting.sky());
        session = RenderEngine.openSession(world, navigation.view(), settings, backend);
        autofocus = new GameAutofocus(navigation, world);
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "sample-game-update");
                            thread.setDaemon(true);
                            return thread;
                        });
        session.onImageAvailable(this::imageAvailable);
        executor.scheduleWithFixedDelay(this::tick, 0, 16, TimeUnit.MILLISECONDS);
    }

    /** Coalesce completion signals; all session work stays on the existing game owner. */
    private void imageAvailable() {
        if (!completionQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(
                    () -> {
                        completionQueued.set(false);
                        tick();
                    });
        } catch (RejectedExecutionException closing) {
            completionQueued.set(false);
            // A completion already in flight may arrive after close shuts down the owner.
        }
    }

    private void tick() {
        RenderImage acquired = null;
        try {
            engine.RenderView view;
            engine.WorldSnapshot capturedWorld;
            RenderSettings capturedSettings;
            float capturedExposure;
            synchronized (this) {
                if (closed) {
                    return;
                }
                advanceClock();
                if (!navigation.active()) {
                    if (!suspended) {
                        session.suspend();
                        suspended = true;
                    }
                    return;
                }
                suspended = false;
                view = navigation.view();
                capturedWorld = world;
                capturedSettings = settings;
                capturedExposure = exposure;
                session.update(capturedWorld, view, capturedSettings);
                session.request();
                acquired = session.acquireImage();
                if (acquired == null || acquired.publicationNanos() == converted) {
                    return;
                }
            }
            // Keep the image leased, but never hold the controls/overlay monitor while converting.
            // Only this executor converts images; input can keep editing immutable settings
            // meanwhile.
            long copyStart = System.nanoTime();
            var display = imageConverter.apply(acquired, capturedExposure);
            long copied = System.nanoTime();
            synchronized (this) {
                if (closed
                        || suspended
                        || !navigation.active()
                        || world != capturedWorld
                        || settings != capturedSettings
                        || exposure != capturedExposure) {
                    return;
                }
                var metrics =
                        publicationObserver == null
                                ? null
                                : new Metrics(
                                        acquired.traceNanos(),
                                        copied - copyStart,
                                        session.progress(acquired).shownAgeNanos(),
                                        copyStart - acquired.finishedNanos(),
                                        acquired.traceStats());
                frame =
                        new Frame(
                                display,
                                view.width(),
                                view.height(),
                                acquired.generation(),
                                acquired.samples(),
                                copied,
                                acquired.publicationNanos(),
                                acquired.camera(),
                                metrics);
                session.presented(acquired);
                converted = acquired.publicationNanos();
                publishClock(copied);
                if (publicationObserver != null) {
                    publicationObserver.accept(frame);
                }
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (!closed) {
                    error = "Rendering failed: " + failure.getMessage();
                    navigation.active(false);
                    session.suspend();
                    suspended = true;
                }
            }
        } finally {
            if (acquired != null) {
                acquired.close();
            }
        }
    }

    private void advanceClock() {
        long now = System.nanoTime();
        double elapsed = (now - lastTick) / 1e9;
        navigation.advance(elapsed);
        if (navigation.active() && !suspended) {
            cycle.advance(elapsed);
        }
        lastTick = now;
    }

    private void publishClock(long copied) {
        // Change transport after presenting a complete image so slow passes are not starved.
        if (cycle.running() && copied - lastLightingUpdate >= 250_000_000L) {
            applyLighting(cycle.lighting());
            lastLightingUpdate = copied;
        }
    }

    /** Convert under the lease, flipping the engine's bottom-up rows to AWT's top-down rows. */
    public static BufferedImage copyImage(RenderImage image) {
        return copyImage(image, 1);
    }

    private static BufferedImage copyImage(RenderImage image, float exposure) {
        var result = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_RGB);
        var pixels = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        var source = image.presentationPixels();
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int red =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(0, x, y), exposure));
                int green =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(1, x, y), exposure));
                int blue =
                        Byte.toUnsignedInt(DisplayMapping.encode(source.value(2, x, y), exposure));
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

    public boolean adaptiveSupported() {
        return backend.equals("cpu");
    }

    synchronized boolean tracing() {
        return session.progress(null).running();
    }

    public synchronized boolean adaptive() {
        return settings.interactive();
    }

    public synchronized boolean refinement() {
        return settings.sampleTarget() != 1;
    }

    public synchronized void refinement(boolean enabled) {
        if (refinement() == enabled) {
            return;
        }
        settings = settings.withSampleTarget(enabled ? refinementTarget : 1);
        if (!enabled) {
            // Discard the refined mean so the next image really contains only sample zero.
            settings = settings.withRestartRevision(settings.restartRevision() + 1);
        }
    }

    public synchronized void adaptive(boolean enabled) {
        if (enabled && backend.equals("gpu")) {
            throw new IllegalArgumentException("GPU supports fixed grids only");
        }
        settings = settings.withInteractive(enabled, settings.interactiveMillis(), 0, 0);
    }

    public synchronized float exposure() {
        return exposure;
    }

    public synchronized long refinementTarget() {
        return refinementTarget;
    }

    public synchronized void exposure(float value) {
        if (!Float.isFinite(value) || value <= 0 || value > 100) {
            throw new IllegalArgumentException(
                    "Exposure must be greater than zero and at most 100");
        }
        if (value != exposure) {
            exposure = value;
            converted = -1;
        }
    }

    public synchronized void sampleLimit(long value) {
        if (value != 0 && (value < 2 || value > 1_000_000_000L)) {
            throw new IllegalArgumentException(
                    "Sample limit must be 0 (unlimited) or 2..1000000000");
        }
        refinementTarget = value;
        if (refinement()) {
            settings =
                    settings.withSampleTarget(value)
                            .withRestartRevision(settings.restartRevision() + 1);
        }
    }

    public synchronized void configure(java.util.function.UnaryOperator<RenderSettings> edit) {
        var next = edit.apply(settings);
        if (!adaptiveSupported()
                && (next.pathDepth() > 2
                        || next.interactive()
                        || next.temporal()
                        || next.temporalBudget()
                        || !next.acceleration())) {
            throw new IllegalArgumentException(
                    "GPU requires depth 0..2, acceleration, fixed resolution and temporal off");
        }
        settings = next;
    }

    public synchronized RenderSettings settings() {
        return settings;
    }

    /** Benchmark-only layout selection; transport and accumulated samples remain unchanged. */
    synchronized void tracingLayout(int workers, int tileSize) {
        settings = settings.withWorkers(workers).withTileSize(tileSize);
    }

    public synchronized long restart() {
        settings = settings.withRestartRevision(settings.restartRevision() + 1);
        session.update(world, navigation.view(), settings);
        return session.progress(null).requestedGeneration();
    }

    /** Benchmark-only observation of every copied publication, serialized with tick and detach. */
    synchronized void observePublications(java.util.function.Consumer<Frame> observer) {
        publicationObserver = observer;
    }

    /** Replace immutable input once per effective edit; reset/undo must not revive old images. */
    public synchronized void lighting(GameLighting value) {
        cycle.running(false);
        applyLighting(value);
    }

    public synchronized double timeOfDay() {
        return cycle.hour();
    }

    public synchronized double dayDuration() {
        return cycle.duration();
    }

    public synchronized boolean dayCycle() {
        return cycle.running();
    }

    public synchronized void dayCycle(boolean value) {
        cycle.running(value);
        if (value) {
            applyLighting(cycle.lighting());
        }
    }

    public synchronized void timeOfDay(double value) {
        cycle.hour(value);
        applyLighting(cycle.lighting());
    }

    public synchronized void dayDuration(double value) {
        cycle.duration(value);
    }

    private void applyLighting(GameLighting value) {
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

    public String backendStatus() {
        return error != null ? error : backend.equals("cpu") ? "CPU" : session.status();
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
            autofocus.close();
            session.onImageAvailable(null);
            executor.shutdownNow();
            session.close();
        }
    }
}
