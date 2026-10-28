package game;

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
            Metrics metrics,
            FrameContext context) {}

    public record FrameContext(
            engine.WorldSnapshot world, RenderSettings settings, float exposure) {}

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

    private final GameRecording recording = new GameRecording();
    private final java.util.LinkedHashMap<Long, FrameContext> frameContexts =
            new java.util.LinkedHashMap<>();
    private final AtomicBoolean exporting = new AtomicBoolean();
    private volatile String captureStatus = "F8: snapshot | F9: last 30 seconds";
    private volatile Frame displayedFrame;
    private final java.util.concurrent.ExecutorService captureWriter =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        var thread = new Thread(runnable, "game-capture-writer");
                        thread.setDaemon(true);
                        return thread;
                    });

    public String captureStatus() {
        return captureStatus;
    }

    void displayed(Frame value) {
        displayedFrame = value;
    }

    public java.util.concurrent.CompletableFuture<java.nio.file.Path> captureBundle(
            boolean history) {
        return captureBundle(history, java.nio.file.Path.of("out/game/captures"));
    }

    java.util.concurrent.CompletableFuture<java.nio.file.Path> captureBundle(
            boolean history, java.nio.file.Path root) {
        if (!exporting.compareAndSet(false, true)) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Capture already saving"));
        }
        GameCapture.Pending pending;
        synchronized (this) {
            synchronized (navigation) {
                long now = System.nanoTime();
                pending =
                        new GameCapture.Pending(
                                world,
                                captureControls(),
                                navigation.snapshot(),
                                displayedFrame,
                                recording.freeze(now, history),
                                backend,
                                cachedBackendStatus,
                                now,
                                history);
            }
        }
        captureStatus = "Saving " + (history ? "30-second history" : "snapshot") + "...";
        var result = new java.util.concurrent.CompletableFuture<java.nio.file.Path>();
        try {
            captureWriter.execute(
                    () -> {
                        try {
                            var path = GameCapture.write(root, pending);
                            captureStatus = "Saved capture: " + path;
                            System.out.println(captureStatus);
                            exporting.set(false);
                            result.complete(path);
                        } catch (Throwable failure) {
                            captureStatus = "Capture failed: " + failure.getMessage();
                            exporting.set(false);
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException closing) {
            exporting.set(false);
            result.completeExceptionally(closing);
        }
        return result;
    }

    synchronized GameCapture.Controls captureControls() {
        return new GameCapture.Controls(
                settings,
                lighting,
                exposure,
                cycle.hour(),
                cycle.duration(),
                cycle.running(),
                refinementTarget,
                atmosphereEnabled,
                atmosphere,
                fogEnabled,
                fogOverlap,
                fogReverse,
                fogDemo,
                fogClosed,
                fogShift,
                fogRegions);
    }

    private void recordControls() {
        recordControls(true);
    }

    private void recordControls(boolean force) {
        recording.observe(
                new GameRecording.Observation(
                        System.nanoTime(), world, captureControls(), navigation.snapshot()),
                force);
    }

    private void rememberFrameContext() {
        frameContexts.putIfAbsent(
                session.progress(null).requestedGeneration(),
                new FrameContext(world, settings, exposure));
        while (frameContexts.size() > 512) {
            frameContexts.remove(frameContexts.keySet().iterator().next());
        }
    }

    /** Restore physical state and controls, then leave playback paused for human inspection. */
    public synchronized void restoreCapture(GameCapture.Loaded capture) {
        var state = capture.manifest().current();
        var controls = state.controls();
        world = capture.world(state.world());
        settings = controls.settings().withPaused(false);
        lighting = controls.lighting();
        exposure = controls.exposure();
        cycle.hour(controls.hour());
        cycle.duration(controls.duration());
        cycle.running(false);
        refinementTarget = controls.refinementTarget();
        atmosphereEnabled = controls.atmosphereEnabled();
        atmosphere = controls.atmosphere();
        fogEnabled = controls.fogEnabled();
        fogOverlap = controls.fogOverlap();
        fogReverse = controls.fogReverse();
        fogDemo = controls.fogDemo();
        fogClosed = controls.fogClosed();
        fogShift = controls.fogShift();
        fogRegions = controls.fogRegions();
        updateCollision(world);
        navigation.restore(state.navigation());
        navigation.release();
        navigation.autofocus(false);
        lastTick = System.nanoTime();
        recordControls();
    }

    private final RuntimeHooks hooks;
    private final java.util.concurrent.atomic.AtomicReference<engine.RenderFailure> fatal =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final GameNavigation navigation;
    private volatile ShooterGame shooter;

    public synchronized void enableShooter() {
        if (shooter == null) {
            shooter = new ShooterGame(world.instances());
            settings = settings.withPathDepth(Math.max(8, settings.pathDepth()));
        }
    }

    public ShooterGame shooter() {
        return shooter;
    }

    private engine.RenderView combatView;
    private boolean combatFrameComplete = true;

    private void publishCombat(engine.Camera camera, float aim) {
        if (shooter == null) {
            return;
        }
        var instances =
                new java.util.ArrayList<>(
                        world.instances().stream()
                                .filter(
                                        instance ->
                                                !instance.name().startsWith("patrol/")
                                                        && !instance.name()
                                                                .startsWith("player/rifle/"))
                                .toList());
        instances.addAll(shooter.instances());
        instances.addAll(RifleMesh.firstPerson(camera, shooter.status().cooldown(), aim));
        if (!instances.equals(world.instances())) {
            world =
                    new engine.WorldSnapshot(
                            world.revision() + 1,
                            instances,
                            world.legacyObjects(),
                            world.lights(),
                            world.sky());
        }
    }

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
        navigation.recording(recording);
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
                        var combat = shooter;
                        if (combat == null || combat.alive()) {
                            navigation.advance((now - previousTick[0]) / 1e9);
                        } else {
                            navigation.advanceAim((now - previousTick[0]) / 1e9);
                        }
                        if (combat != null) {
                            combat.advance(
                                    (now - previousTick[0]) / 1e9,
                                    navigation.view().camera(),
                                    navigation.active() && navigation.captured());
                        }
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
        navigation.collisionSource(collisionInstances);
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

    private engine.RenderView captureCombatView() {
        if (shooter == null) {
            return navigation.view();
        }
        // Camera-attached geometry and its camera must remain one immutable frame.
        // Advance only after publication so continuous input cannot cancel every pass.
        if (combatFrameComplete || combatView == null) {
            var previousWorld = world;
            var previousView = combatView;
            synchronized (navigation) {
                combatView = navigation.view();
                publishCombat(combatView.camera(), navigation.aimAmount());
            }
            // An unchanged, converged request may produce no new publication.
            combatFrameComplete = world == previousWorld && combatView.equals(previousView);
        }
        return combatView;
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
                view = captureCombatView();
                capturedWorld = world;
                capturedSettings = settings;
                capturedExposure = exposure;
                session.update(capturedWorld, view, capturedSettings);
                if (!Boolean.getBoolean("game.referenceDisplay")) {
                    session.displayExposure(capturedExposure);
                }
                session.request();
                rememberFrameContext();
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
                                metrics,
                                contextFor(acquired.generation(), capturedExposure));
                recording.picture(frame);
                session.presented(acquired);
                cachedBackendStatus = session.status();
                captureDiagnostics(view, session.progress(acquired), cachedBackendStatus);
                converted = acquired.publicationNanos();
                publishClock(copied);
                combatFrameComplete = true;
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

    private FrameContext contextFor(long generation, float capturedExposure) {
        var context = frameContexts.get(generation);
        return context == null
                ? null
                : new FrameContext(context.world(), context.settings(), capturedExposure);
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
        recordControls(false);
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
        image.presentationPixels().copyDisplayTo(pixels, exposure);
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
        recordControls();
    }

    public synchronized void adaptive(boolean enabled) {
        capabilities.require(
                engine.RenderCapabilities.Feature.ADAPTIVE_RESOLUTION,
                enabled,
                "settings.interactive");
        settings = settings.withInteractive(enabled, settings.interactiveMillis(), 0, 0);
        recordControls();
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
        recordControls();
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
        recordControls();
    }

    public synchronized void configure(java.util.function.UnaryOperator<RenderSettings> edit) {
        var next = edit.apply(settings);
        if (shooter != null && next.pathDepth() < 8) {
            throw new IllegalArgumentException(
                    "The physical scope requires path depth 8 or higher");
        }
        capabilities.validate(world, navigation.view(), next);
        settings = next;
        recordControls();
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
        recordControls();
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
        recordControls();
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
        recordControls();
    }

    public synchronized void timeOfDay(double value) {
        cycle.hour(value);
        applyLighting(cycle.lighting());
        recordControls();
    }

    public synchronized void dayDuration(double value) {
        cycle.duration(value);
        recordControls();
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
        recordControls();
    }

    public synchronized void fogOverlap(boolean value) {
        changeFog(fogEnabled, value, fogReverse, fogDemo, fogClosed, fogShift, fogRegions);
        recordControls();
    }

    public synchronized void fogReverse(boolean value) {
        changeFog(fogEnabled, fogOverlap, value, fogDemo, fogClosed, fogShift, fogRegions);
        recordControls();
    }

    public synchronized void fogClosed(boolean value) {
        changeFog(fogEnabled, fogOverlap, fogReverse, true, value, fogShift, fogRegions);
        recordControls();
    }

    public synchronized void fogShift(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 10) {
            throw new IllegalArgumentException("Fog screen shift must be -10..10 world units");
        }
        changeFog(fogEnabled, fogOverlap, fogReverse, true, fogClosed, (float) value, fogRegions);
        recordControls();
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
        recordControls();
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
        recordControls();
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
        recordControls();
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
        recordControls();
    }

    public synchronized void atmosphere(engine.Atmosphere.Parameters parameters) {
        java.util.Objects.requireNonNull(parameters);
        if (atmosphere.equals(parameters)) {
            return;
        }
        changeAtmosphere(atmosphereEnabled, parameters);
        recordControls();
    }

    public synchronized void atmospherePreset(boolean hazy) {
        var parameters =
                hazy
                        ? engine.Atmosphere.Parameters.hazyMorning()
                        : engine.Atmosphere.Parameters.clearAir();
        // Presets alter only optical inputs, retaining time, optics, exposure and scene edits.
        changeAtmosphere(true, parameters);
        recordControls();
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
            navigation.recording(null);
            captureWriter.shutdown();
            simulation.shutdownNow();
            executor.shutdownNow();
            session.close();
        }
    }
}
