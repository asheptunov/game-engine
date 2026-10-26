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

    @FunctionalInterface
    interface SessionFactory {
        RenderSession open(
                engine.WorldSnapshot world,
                engine.RenderView view,
                RenderSettings settings,
                String backend);
    }

    record RuntimeHooks(
            GameDiagnostics diagnostics,
            java.util.function.Consumer<engine.RenderFailure> fatal,
            SessionFactory factory) {
        static RuntimeHooks defaults() {
            return new RuntimeHooks(
                    new GameDiagnostics(), failure -> {}, RenderEngine::openSession);
        }
    }

    private final RuntimeHooks hooks;
    private final java.util.concurrent.atomic.AtomicReference<engine.RenderFailure> fatal =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final GameNavigation navigation;
    private final String backend;
    private final engine.RenderCapabilities capabilities;
    private engine.WorldSnapshot world;
    private GameLighting lighting = GameLighting.defaults();
    private final DayNightCycle cycle = new DayNightCycle();
    private long lastLightingUpdate;
    private boolean fogEnabled;
    private boolean fogOverlap;
    private boolean fogReverse;
    private boolean fogDemo;
    private boolean fogClosed;
    private float fogShift;
    private java.util.List<engine.FogRegion> fogRegions =
            java.util.List.of(RuinsMist.region(0), RuinsMist.region(1));
    private boolean atmosphereEnabled;
    private engine.Atmosphere.Parameters atmosphere = engine.Atmosphere.Parameters.clearAir();
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
    private final ScheduledExecutorService simulation;
    private java.util.List<engine.SceneInstance> collisionInstances;
    private final AtomicBoolean completionQueued = new AtomicBoolean();
    private volatile Frame frame;
    private volatile String error;
    private String cachedBackendStatus = "session initialized; no completed image";
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
        this(navigation, source, backend, imageConverter, RuntimeHooks.defaults());
    }

    GameRenderer(
            GameNavigation navigation,
            engine.WorldSnapshot source,
            String backend,
            java.util.function.BiFunction<RenderImage, Float, BufferedImage> imageConverter,
            RuntimeHooks hooks) {
        this.hooks = hooks;
        this.imageConverter = java.util.Objects.requireNonNull(imageConverter);
        this.navigation = navigation;
        this.backend = backend;
        capabilities = RenderEngine.capabilities(backend);
        if (!adaptiveSupported() || navigation.fixedResolution()) {
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
        updateCollision(world);
        captureDiagnostics(navigation.view(), null, "session initialization pending");
        session = hooks.factory().open(world, navigation.view(), settings, backend);
        autofocus = new GameAutofocus(navigation, world);
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "sample-game-update");
                            thread.setDaemon(true);
                            return thread;
                        });
        session.onImageAvailable(this::imageAvailable);
        session.onFailure(this::failed);
        hooks.diagnostics().event("session created: " + backend);
        simulation =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            var thread = new Thread(runnable, "sample-game-physics");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(this::tick, 0, 16, TimeUnit.MILLISECONDS);
        final long[] previousTick = {System.nanoTime()};
        simulation.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.nanoTime();
                        navigation.advance((now - previousTick[0]) / 1e9);
                        previousTick[0] = now;
                    } catch (Throwable failure) {
                        navigation.active(false);
                        failed(asFailure(backend, "physics", failure));
                    }
                },
                0,
                4,
                TimeUnit.MILLISECONDS);
    }

    private void updateCollision(engine.WorldSnapshot value) {
        if (value.instances().equals(collisionInstances)) {
            return;
        }
        boolean meshes =
                value.legacyObjects().isEmpty()
                        && value.instances().stream()
                                .allMatch(
                                        instance ->
                                                instance.geometry() instanceof engine.PolygonMesh);
        if (!meshes && navigation.walking()) {
            navigation.walking(false);
        }
        navigation.collision(meshes ? new engine.CapsuleCollision(value.instances()) : null);
        collisionInstances = value.instances();
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
                if (closed || fatal.get() != null) {
                    return;
                }
                advanceClock();
                captureDiagnostics(navigation.view(), session.progress(null), cachedBackendStatus);
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
                captureDiagnostics(view, session.progress(null), cachedBackendStatus);
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
                        new Metrics(
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
                cachedBackendStatus = session.status();
                captureDiagnostics(view, session.progress(acquired), cachedBackendStatus);
                converted = acquired.publicationNanos();
                publishClock(copied);
                if (publicationObserver != null) {
                    publicationObserver.accept(frame);
                }
            }
        } catch (Throwable failure) {
            failed(asFailure(backend, "presentation/execution", failure));
        } finally {
            if (acquired != null) {
                acquired.close();
            }
        }
    }

    static engine.RenderFailure asFailure(String backend, String phase, Throwable failure) {
        return failure instanceof engine.RenderFailure reported
                ? reported
                : new engine.RenderFailure(backend, phase, failure, java.util.Map.of());
    }

    private void failed(engine.RenderFailure failure) {
        if (fatal.compareAndSet(null, failure)) {
            error = "Rendering failed: " + failure.getMessage();
            hooks.diagnostics().event("fatal " + failure.phase());
            hooks.fatal().accept(failure);
        }
    }

    private void captureDiagnostics(
            engine.RenderView view, engine.RenderProgress progress, String status) {
        hooks.diagnostics()
                .capture(
                        new GameDiagnostics.State(
                                world,
                                view,
                                settings,
                                lighting,
                                cycle.hour(),
                                cycle.duration(),
                                cycle.running(),
                                exposure,
                                navigation.active(),
                                progress,
                                frame,
                                status));
    }

    private void advanceClock() {
        long now = System.nanoTime();
        double elapsed = (now - lastTick) / 1e9;
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

    static BufferedImage copyImage(RenderImage image, float exposure) {
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

    public engine.RenderCapabilities capabilities() {
        return capabilities;
    }

    public boolean adaptiveSupported() {
        return capabilities
                .support(engine.RenderCapabilities.Feature.ADAPTIVE_RESOLUTION)
                .supported();
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
        capabilities.require(
                engine.RenderCapabilities.Feature.ADAPTIVE_RESOLUTION,
                enabled,
                "settings.interactive");
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
        capabilities.validate(world, navigation.view(), next);
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
        captureDiagnostics(navigation.view(), session.progress(null), cachedBackendStatus);
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

    /** Presets select the same clock time regardless of the sky rendering mode. */
    public synchronized void lightingPreset(GameLighting.Preset preset) {
        java.util.Objects.requireNonNull(preset);
        cycle.running(false);
        timeOfDay(
                switch (preset) {
                    case MORNING -> 7;
                    case NOON -> 12;
                    case EVENING -> 17;
                });
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

    public synchronized boolean fogEnabled() {
        return fogEnabled;
    }

    public synchronized boolean fogOverlap() {
        return fogOverlap;
    }

    public synchronized boolean fogReverse() {
        return fogReverse;
    }

    public synchronized boolean fogClosed() {
        return fogClosed;
    }

    public synchronized float fogShift() {
        return fogShift;
    }

    public synchronized engine.FogRegion fogRegion(int index) {
        return fogRegions.get(index);
    }

    public synchronized void fogEnabled(boolean value) {
        changeFog(value, fogOverlap, fogReverse, fogDemo, fogClosed, fogShift, fogRegions);
    }

    public synchronized void fogOverlap(boolean value) {
        changeFog(fogEnabled, value, fogReverse, fogDemo, fogClosed, fogShift, fogRegions);
    }

    public synchronized void fogReverse(boolean value) {
        changeFog(fogEnabled, fogOverlap, value, fogDemo, fogClosed, fogShift, fogRegions);
    }

    public synchronized void fogClosed(boolean value) {
        changeFog(fogEnabled, fogOverlap, fogReverse, true, value, fogShift, fogRegions);
    }

    public synchronized void fogShift(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 10) {
            throw new IllegalArgumentException("Fog screen shift must be -10..10 world units");
        }
        changeFog(fogEnabled, fogOverlap, fogReverse, true, fogClosed, (float) value, fogRegions);
    }

    public synchronized void fogRegion(int index, engine.FogRegion value) {
        var regions = new java.util.ArrayList<>(fogRegions);
        regions.set(index, java.util.Objects.requireNonNull(value));
        changeFog(
                fogEnabled,
                fogOverlap,
                fogReverse,
                fogDemo,
                fogClosed,
                fogShift,
                java.util.List.copyOf(regions));
    }

    public synchronized void fogReset() {
        changeFog(
                false,
                false,
                false,
                false,
                false,
                0,
                java.util.List.of(RuinsMist.region(0), RuinsMist.region(1)));
    }

    public synchronized void fogPreset() {
        changeFog(
                true,
                false,
                false,
                true,
                false,
                0,
                java.util.List.of(RuinsMist.region(0), RuinsMist.region(1)));
        cycle.running(false);
        timeOfDay(7.5);
    }

    private void changeFog(
            boolean enabled,
            boolean overlap,
            boolean reverse,
            boolean demo,
            boolean closed,
            float shift,
            java.util.List<engine.FogRegion> regions) {
        var active = activeFog(enabled, overlap, reverse, regions);
        var candidate =
                new engine.WorldSnapshot(
                        world.revision() + 1,
                        RuinsMist.scenery(world.instances(), demo, closed, shift),
                        world.legacyObjects(),
                        world.lights(),
                        world.sky().withFog(active));
        fogEnabled = enabled;
        fogOverlap = overlap;
        fogReverse = reverse;
        fogDemo = demo;
        fogClosed = closed;
        fogShift = shift;
        fogRegions = regions;
        updateCollision(candidate);
        world = candidate;
    }

    private static java.util.List<engine.FogRegion> activeFog(
            boolean enabled,
            boolean overlap,
            boolean reverse,
            java.util.List<engine.FogRegion> regions) {
        if (!enabled) {
            return java.util.List.of();
        }
        if (!overlap) {
            return java.util.List.of(regions.getFirst());
        }
        return reverse ? java.util.List.of(regions.get(1), regions.getFirst()) : regions;
    }

    public synchronized boolean atmosphereEnabled() {
        return atmosphereEnabled;
    }

    public synchronized engine.Atmosphere.Parameters atmosphere() {
        return atmosphere;
    }

    public synchronized void atmosphereEnabled(boolean enabled) {
        if (atmosphereEnabled == enabled) {
            return;
        }
        changeAtmosphere(enabled, atmosphere);
    }

    public synchronized void atmosphere(engine.Atmosphere.Parameters parameters) {
        java.util.Objects.requireNonNull(parameters);
        if (atmosphere.equals(parameters)) {
            return;
        }
        changeAtmosphere(atmosphereEnabled, parameters);
    }

    public synchronized void atmospherePreset(boolean hazy) {
        var parameters =
                hazy
                        ? engine.Atmosphere.Parameters.hazyMorning()
                        : engine.Atmosphere.Parameters.clearAir();
        // Presets alter only optical inputs, retaining time, optics, exposure and scene edits.
        changeAtmosphere(true, parameters);
    }

    private void changeAtmosphere(boolean enabled, engine.Atmosphere.Parameters parameters) {
        var replacement = lightingWorld(lighting, enabled, parameters);
        // Validate the entire combination before committing either controls or world state.
        atmosphere = parameters;
        atmosphereEnabled = enabled;
        world = replacement;
    }

    private void applyLighting(GameLighting value) {
        var replacement = lightingWorld(value, atmosphereEnabled, atmosphere);
        lighting = value;
        world = replacement;
    }

    private engine.WorldSnapshot lightingWorld(
            GameLighting value, boolean enabled, engine.Atmosphere.Parameters parameters) {
        var effective = enabled ? cycle.atmosphericLighting(parameters) : value;
        var sky =
                effective.sky().withFog(activeFog(fogEnabled, fogOverlap, fogReverse, fogRegions));
        var lights = java.util.List.<engine.lights.Light>of(effective.sun());
        if (world.sky().equals(sky) && world.lights().equals(lights)) {
            return world;
        }
        return new engine.WorldSnapshot(
                world.revision() + 1, world.instances(), world.legacyObjects(), lights, sky);
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
        return closed && session.closed() && executor.isShutdown() && simulation.isShutdown();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            navigation.active(false);
            autofocus.close();
            session.onImageAvailable(null);
            simulation.shutdownNow();
            executor.shutdownNow();
            session.close();
        }
    }
}
