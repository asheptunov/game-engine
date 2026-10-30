package gpu;

import engine.Camera;
import engine.RenderImage;
import engine.RenderProgress;
import engine.RenderSession;
import engine.RenderSettings;
import engine.RenderView;
import engine.RgbPixels;
import engine.WorldSnapshot;

import profiling.TraceProfile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Three bounded publication slots and one GPU owner; Vulkan calls stay on that owner. */
public final class GpuRenderSession implements RenderSession {
    private record Key(
            WorldSnapshot world,
            RenderView view,
            int depth,
            long seed,
            long restart,
            int fogDownsample,
            boolean fogLightingGrid,
            boolean fogJitter,
            boolean foveated,
            boolean smoothFoveation,
            engine.FoveationQuality foveationQuality,
            boolean temporal,
            long temporalRevision,
            long temporalEpoch,
            double lightingScale,
            RenderView presentationView,
            engine.Denoiser denoiser,
            boolean pathRoulette) {}

    private static final class Slot {
        volatile float[] pixels;
        int[] packed;
        float exposure;
        engine.Bloom bloom = engine.Bloom.OFF;
        boolean deviceOnly;
        GpuKernel.Snapshot snapshot;
        GpuKernel.Snapshot presentation;
        volatile float[] shownPixels;
        boolean reconstructed;
        long temporalRevision;
        long temporalEpoch;
        String historyLabel;
        int readers;
        boolean writing;
        RenderView view;
        RenderView presentationView;
        long generation;
        long captured;
        long finished;
        long samples;
        long trace;
        TraceProfile.Stats stats;
    }

    /**
     * Optional benchmark timings in nanoseconds for the last sample of a published batch.
     * capturedNanos uses System.nanoTime; samples is the accumulated publication count. GPU
     * durations overlap submitWaitNanos, which includes host submission and fence waiting.
     */
    public record FrameTiming(
            long capturedNanos,
            long samples,
            int batchSamples,
            long hostNanos,
            long prepareNanos,
            long executeNanos,
            long transferNanos,
            long submitWaitNanos,
            long readbackNanos) {}

    private final boolean profileFrames = Boolean.getBoolean("game.profileFrames");
    private final java.util.ArrayDeque<FrameTiming> frameTimings = new java.util.ArrayDeque<>();

    /**
     * Returns an immutable snapshot; collection is disabled unless requested at session creation.
     */
    public synchronized java.util.List<FrameTiming> frameTimings() {
        return java.util.List.copyOf(frameTimings);
    }

    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private final ExecutorService owner =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        var thread = new Thread(runnable, "gpu-render-owner");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile boolean devicePresentation;
    private final java.util.ArrayDeque<GpuKernel.Snapshot> recycledDisplay =
            new java.util.ArrayDeque<>();
    private final java.util.Set<GpuDevicePixels> displayFrames = new java.util.HashSet<>();
    private WorldSnapshot world;
    private RenderView view;
    private RenderSettings settings;
    private Key key;
    private Float displayExposure;
    private final java.util.concurrent.CompletableFuture<Void> retired =
            new java.util.concurrent.CompletableFuture<>();
    private long activeGeneration = -1;
    private long generation;
    private long epoch;
    private long cancelled;
    private boolean closed;
    private boolean suspended;
    private boolean running;
    private volatile engine.RenderFailure failure;
    private volatile java.util.function.Consumer<engine.RenderFailure> failureListener;
    private String diagnostics = "GPU initializing; fixed grid; primitive counters unavailable";
    private Runnable listener;
    private Slot latest;
    private final GpuResolutionPolicy resolution = new GpuResolutionPolicy();
    private long measuredPublication = -1;
    // The fields below belong only to the GPU owner.
    private GpuKernel kernel;
    private final java.util.function.Supplier<GpuKernel> kernelFactory;
    private Key accumulatedKey;
    private long accumulatedSamples;
    private volatile long historyReset;
    private long appliedHistoryReset;
    private long temporalEpoch;

    public GpuRenderSession(WorldSnapshot world, RenderView view, RenderSettings settings) {
        this(world, view, settings, GpuKernel::new);
    }

    GpuRenderSession(
            WorldSnapshot world,
            RenderView view,
            RenderSettings settings,
            java.util.function.Supplier<GpuKernel> kernelFactory) {
        this.kernelFactory = kernelFactory;
        update(world, view, settings);
    }

    @Override
    public void update(WorldSnapshot nextWorld, RenderView nextView, RenderSettings nextSettings) {
        GpuKernel.validate(nextWorld, nextView, nextSettings);
        if (nextSettings.temporal()
                && nextSettings.denoiser() == engine.Denoiser.NRD_RELAX
                && !GpuNrdLibrary.available()) {
            throw new IllegalStateException(
                    "NRD runtime is missing; run ./setup-nrd.ps1 or choose Built-in diffuse");
        }
        synchronized (this) {
            checkOpen();
            updateTemporalEpoch(nextView, nextSettings);
            var nextKey =
                    new Key(
                            nextWorld,
                            nextView,
                            nextSettings.pathDepth(),
                            nextSettings.seed(),
                            nextSettings.restartRevision(),
                            nextSettings.fogDownsample(),
                            nextSettings.fogLightingGrid(),
                            nextSettings.fogJitter(),
                            nextSettings.foveated(),
                            nextSettings.foveated() && nextSettings.smoothFoveation(),
                            effectiveQuality(nextSettings),
                            nextSettings.temporal(),
                            nextSettings.temporalRevision(),
                            temporalEpoch,
                            nextSettings.lightingScale(),
                            nextView,
                            nextSettings.denoiser(),
                            nextSettings.pathRoulette());
            resolution.observe(nextView, System.nanoTime());
            if (!nextKey.equals(key)) {
                generation++;
                boolean cameraOnly =
                        key != null
                                && nextWorld.equals(world)
                                && nextView.width() == view.width()
                                && nextView.height() == view.height()
                                && nextSettings.pathDepth() == settings.pathDepth()
                                && nextSettings.seed() == settings.seed()
                                && nextSettings.restartRevision() == settings.restartRevision()
                                && nextSettings.fogDownsample() == settings.fogDownsample()
                                && nextSettings.fogLightingGrid() == settings.fogLightingGrid()
                                && nextSettings.fogJitter() == settings.fogJitter()
                                && sameSampling(nextSettings, settings)
                                && nextSettings.temporal() == settings.temporal()
                                && nextSettings.temporalRevision() == settings.temporalRevision()
                                && nextSettings.lightingScale() == settings.lightingScale()
                                && nextSettings.denoiser() == settings.denoiser()
                                && nextSettings.pathRoulette() == settings.pathRoulette();
                if (!cameraOnly) {
                    epoch++;
                }
                key = nextKey;
            }
            if (settings != null
                    && (settings.paused() != nextSettings.paused()
                            || settings.sampleTarget() != nextSettings.sampleTarget()
                            || settings.pathRoulette() != nextSettings.pathRoulette()
                            || settings.denoiser() != nextSettings.denoiser()
                            || settings.interactive() != nextSettings.interactive()
                            || !sameSampling(settings, nextSettings))) {
                epoch++;
                resolution.reset();
                historyReset++;
            }
            world = nextWorld;
            view = nextView;
            settings = nextSettings;
        }
    }

    @Override
    public engine.RenderCapabilities.Support denoiserSupport(engine.Denoiser denoiser) {
        boolean available = denoiser == engine.Denoiser.BUILT_IN || GpuNrdLibrary.available();
        return new engine.RenderCapabilities.Support(
                available,
                available
                        ? "Denoiser runtime available"
                        : "Run ./setup-nrd.ps1 to install the optional NRD runtime");
    }

    @Override
    public synchronized void invalidate() {
        checkOpen();
    }

    private void updateTemporalEpoch(RenderView nextView, RenderSettings nextSettings) {
        if (settings != null
                && (settings.temporal() != nextSettings.temporal()
                        || settings.temporalRevision() != nextSettings.temporalRevision()
                        || settings.lightingScale() != nextSettings.lightingScale()
                        || settings.pathRoulette() != nextSettings.pathRoulette()
                        || settings.denoiser() != nextSettings.denoiser()
                        || settings.interactive() != nextSettings.interactive()
                        || view.camera().aperture() != nextView.camera().aperture()
                        || !sameSampling(settings, nextSettings)
                        || settings.fogDownsample() != nextSettings.fogDownsample())) {
            temporalEpoch++;
            historyReset++;
        }
    }

    @Override
    public synchronized void displayExposure(float multiplier) {
        checkOpen();
        if (!Float.isFinite(multiplier) || multiplier < 0) {
            throw new IllegalArgumentException("Display exposure must be finite and nonnegative");
        }
        displayExposure = multiplier;
    }

    @Override
    public synchronized void devicePresentation(boolean enabled) {
        checkOpen();
        devicePresentation = enabled;
    }

    @Override
    public synchronized void request() {
        checkOpen();
        if (failure != null) {
            throw failure;
        }
        suspended = false;
        if (running || settings.paused()) {
            return;
        }
        long now = System.nanoTime();
        var presentationView =
                GpuLighting.enabled(view, settings) ? view : resolution.choose(view, settings, now);
        var sampledView = GpuLighting.sampled(presentationView, settings);
        long target = settings.sampleTarget() == 0 ? 1_000_000_000L : settings.sampleTarget();
        if (latest != null
                && latest.generation == generation
                && latest.samples >= target
                && latest.view.width() == sampledView.width()
                && latest.view.height() == sampledView.height()) {
            return;
        }
        Slot available = null;
        for (var slot : slots) {
            if (slot != latest && slot.readers == 0 && !slot.writing) {
                available = slot;
                break;
            }
        }
        if (available == null) {
            return;
        }
        var destination = available;
        destination.writing = true;
        destination.presentationView = presentationView;
        preparePresentation(destination);
        running = true;
        activeGeneration = generation;
        var capturedKey =
                new Key(
                        key.world(),
                        sampledView,
                        key.depth(),
                        key.seed(),
                        key.restart(),
                        key.fogDownsample(),
                        key.fogLightingGrid(),
                        key.fogJitter(),
                        key.foveated(),
                        key.smoothFoveation(),
                        key.foveationQuality(),
                        key.temporal(),
                        key.temporalRevision(),
                        key.temporalEpoch(),
                        key.lightingScale(),
                        presentationView,
                        key.denoiser(),
                        key.pathRoulette());
        var capturedSettings =
                settings.interactive() && resolution.moving(now)
                        ? settings.withSamplesPerBatch(1)
                        : settings;
        long capturedGeneration = generation;
        long capturedEpoch = epoch;
        long capturedTime = System.nanoTime();
        owner.execute(
                () ->
                        render(
                                destination,
                                capturedKey,
                                capturedSettings,
                                capturedGeneration,
                                capturedEpoch,
                                capturedTime));
    }

    private void preparePresentation(Slot destination) {
        destination.reconstructed = GpuTemporal.fallback(view, settings) == null;
        destination.temporalRevision = settings.temporalRevision();
        destination.temporalEpoch = temporalEpoch;
        if (destination.reconstructed && destination.presentation == null) {
            destination.presentation = new GpuKernel.Snapshot();
        }
        destination.deviceOnly = devicePresentation;
        destination.bloom = settings.bloom();
        if (displayExposure != null || devicePresentation) {
            destination.exposure = displayExposure == null ? 1 : displayExposure;
            if (destination.snapshot == null) {
                destination.snapshot = new GpuKernel.Snapshot();
            }
        }
    }

    private static engine.FoveationQuality effectiveQuality(RenderSettings value) {
        return value.foveated() && value.smoothFoveation()
                ? value.foveationQuality()
                : engine.FoveationQuality.defaults();
    }

    private static boolean sameSampling(RenderSettings first, RenderSettings second) {
        return first.foveated() == second.foveated()
                && (!first.foveated()
                        || (first.smoothFoveation() == second.smoothFoveation()
                                && effectiveQuality(first).equals(effectiveQuality(second))));
    }

    private static String samplingLabel(RenderSettings settings) {
        if (!settings.foveated()) {
            return "uniform";
        }
        return settings.smoothFoveation()
                ? "smooth center "
                        + Math.round(settings.foveationQuality().centerFraction() * 100)
                        + "% / "
                        + settings.foveationQuality().edgeSpacing()
                        + "px"
                : "center weighted";
    }

    // Called under the session monitor; records only scalar metadata, never image storage.
    private void recordFrameTiming(long capturedTime, int completed) {
        if (!profileFrames) {
            return;
        }
        if (frameTimings.size() == 4096) {
            frameTimings.removeFirst();
        }
        frameTimings.addLast(
                new FrameTiming(
                        capturedTime,
                        accumulatedSamples,
                        completed,
                        kernel.hostNanos,
                        kernel.prepareNanos,
                        kernel.executeNanos,
                        kernel.transferNanos,
                        kernel.submitWaitNanos,
                        kernel.readbackNanos));
    }

    private void applyHistoryReset() {
        long requestedReset = historyReset;
        if (appliedHistoryReset != requestedReset) {
            kernel.temporal.reset();
            appliedHistoryReset = requestedReset;
        }
    }

    private void render(
            Slot destination,
            Key capturedKey,
            RenderSettings capturedSettings,
            long capturedGeneration,
            long capturedEpoch,
            long capturedTime) {
        Runnable signal;
        try {
            if (kernel == null) {
                kernel = kernelFactory.get();
            }
            applyHistoryReset();
            if (!capturedKey.equals(accumulatedKey)) {
                accumulatedKey = capturedKey;
                accumulatedSamples = 0;
            }
            var capturedView = capturedKey.view();
            prepareDestination(destination, capturedView);
            long target =
                    capturedSettings.sampleTarget() == 0
                            ? 1_000_000_000L
                            : capturedSettings.sampleTarget();
            int batch =
                    (int) Math.min(capturedSettings.samplesPerBatch(), target - accumulatedSamples);
            int completed =
                    traceBatch(
                            destination,
                            capturedKey,
                            capturedSettings,
                            capturedGeneration,
                            capturedEpoch,
                            batch);
            synchronized (this) {
                if (!closed && !suspended && capturedEpoch == epoch) {
                    destination.view = capturedView;
                    destination.reconstructed = kernel.temporal.active;
                    destination.historyLabel = kernel.temporal.label;
                    destination.generation = capturedGeneration;
                    destination.captured = capturedTime;
                    destination.finished = System.nanoTime();
                    destination.samples = accumulatedSamples;
                    destination.trace = kernel.hostNanos;
                    destination.stats =
                            new TraceProfile.Stats(
                                    -1,
                                    -1,
                                    -1,
                                    -1,
                                    "GPU diffuse",
                                    capturedSettings.pathDepth(),
                                    completed,
                                    -1,
                                    -1,
                                    accumulatedSamples,
                                    "GPU; primitive counters unavailable",
                                    capturedSettings.seed(),
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0);
                    recordFrameTiming(capturedTime, completed);
                    latest = destination;
                    diagnostics =
                            String.format(
                                            java.util.Locale.ROOT,
                                            "GPU %s | %s | execute %.2f ms | transfer %.2f ms |"
                                                + " host %.2f ms | prepare %.2f ms | peak %.1f MiB"
                                                + " | primitive counters n/a | init %.2f ms | max"
                                                + " prepare %.2f ms | fog build %.2f ms | fog"
                                                + " builds %d | fog grid 1/%d requested 1/%d | fog"
                                                + " primary %.2f ms | fog reused %d | lighting grid"
                                                + " %b requested %b | grid build %.2f ms | fog"
                                                + " jitter %b | display %s | sampling %s",
                                            kernel.gpu.name,
                                            resolutionLabel(capturedSettings),
                                            kernel.executeNanos / 1e6,
                                            kernel.transferNanos / 1e6,
                                            kernel.hostNanos / 1e6,
                                            kernel.prepareNanos / 1e6,
                                            kernel.gpu.peakBytes / 1048576.0,
                                            kernel.initializationNanos / 1e6,
                                            kernel.maximumPrepareNanos / 1e6,
                                            kernel.fogBuildNanos / 1e6,
                                            kernel.fogBuilds,
                                            kernel.fogDownsample,
                                            capturedSettings.fogDownsample(),
                                            kernel.fogPrimaryNanos / 1e6,
                                            kernel.fogReusedPixels,
                                            kernel.lightingGrid,
                                            capturedSettings.fogLightingGrid(),
                                            kernel.lightingBuildNanos / 1e6,
                                            capturedSettings.fogJitter(),
                                            destination.deviceOnly
                                                    ? "GPU resident"
                                                    : destination.packed == null
                                                            ? "CPU RGB"
                                                            : "GPU packed",
                                            samplingLabel(capturedSettings))
                                    + " | path roulette "
                                    + capturedSettings.pathRoulette()
                                    + String.format(
                                            java.util.Locale.ROOT,
                                            " | %s | reconstruction %.2f ms | visibility %.2f ms |"
                                                    + " lighting %dx%d output %dx%d",
                                            lightingLabel(capturedSettings),
                                            kernel.reconstructionNanos / 1e6,
                                            kernel.visibilityNanos / 1e6,
                                            capturedView.width(),
                                            capturedView.height(),
                                            destination.presentationView.width(),
                                            destination.presentationView.height());
                } else {
                    cancelled++;
                    accumulatedKey = null;
                }
            }
        } catch (Throwable error) {
            recordFailure(error, capturedKey, capturedGeneration);
        } finally {
            synchronized (this) {
                destination.writing = false;
                running = false;
                signal = closed ? null : listener;
            }
        }
        if (signal != null) {
            signal.run();
        }
    }

    private static String resolutionLabel(RenderSettings settings) {
        return settings.interactive() ? "adaptive" : "fixed";
    }

    private String lightingLabel(RenderSettings capturedSettings) {
        return capturedSettings.lightingScale() < 1 && capturedSettings.interactive()
                ? kernel.temporal.label + "; lighting scale inactive: fixed resolution required"
                : kernel.temporal.label;
    }

    private static void prepareDestination(Slot destination, RenderView capturedView) {
        destination.shownPixels = null;
        int length = capturedView.width() * capturedView.height() * 3;
        int displayCount =
                destination.presentationView.width() * destination.presentationView.height();
        if (destination.snapshot != null) {
            destination.pixels = null;
            if (destination.deviceOnly) {
                destination.packed = null;
            } else if (destination.packed == null || destination.packed.length != displayCount) {
                destination.packed = new int[displayCount];
            }
        } else if (destination.pixels == null || destination.pixels.length != length) {
            destination.pixels = new float[length];
        }
    }

    private void recordFailure(Throwable error, Key capturedKey, long capturedGeneration) {
        if (failure == null) {
            failure = describeFailure(error, capturedKey, capturedGeneration);
            var callback = failureListener;
            if (callback != null) {
                callback.accept(failure);
            }
        }
        if (kernel != null) {
            try {
                kernel.close();
            } catch (Throwable cleanup) {
                error.addSuppressed(cleanup);
            }
            kernel = null;
        }
    }

    private engine.RenderFailure describeFailure(
            Throwable error, Key capturedKey, long capturedGeneration) {
        return error instanceof engine.RenderFailure reported
                ? reported
                : new engine.RenderFailure(
                        "gpu",
                        kernel == null ? "initialization" : "execution",
                        error,
                        java.util.Map.of(
                                "requestedGeneration",
                                Long.toString(generation),
                                "activeGeneration",
                                Long.toString(capturedGeneration),
                                "camera",
                                capturedKey.view().toString(),
                                "device",
                                kernel == null ? "unavailable" : kernel.gpu.description,
                                "initialization",
                                kernel == null
                                        ? "kernel creation incomplete"
                                        : "device and pipeline ready",
                                "resources",
                                diagnostics,
                                "lastBackendCompletion",
                                completedContext(),
                                "memoryTiming",
                                kernel == null
                                        ? "unavailable"
                                        : "peakDeviceBytes="
                                                + kernel.gpu.peakBytes
                                                + "; initializationNanos="
                                                + kernel.initializationNanos
                                                + "; executeNanos="
                                                + kernel.executeNanos));
    }

    private synchronized String completedContext() {
        return latest == null
                ? "unavailable (before first frame)"
                : "generation="
                        + latest.generation
                        + ", samples="
                        + latest.samples
                        + ", view="
                        + latest.view;
    }

    private int traceBatch(
            Slot destination,
            Key capturedKey,
            RenderSettings capturedSettings,
            long capturedGeneration,
            long capturedEpoch,
            int batch) {
        var capturedView = capturedKey.view();
        int completed = 0;
        for (; completed < batch; completed++) {
            kernel.trace(
                    capturedKey.world(),
                    capturedView,
                    capturedSettings,
                    accumulatedSamples,
                    new GpuKernel.Destination(
                            destination.pixels,
                            destination.packed,
                            destination.snapshot,
                            destination.exposure,
                            destination.reconstructed ? destination.presentation : null,
                            capturedKey.presentationView()));
            accumulatedSamples++;
            synchronized (this) {
                if (closed
                        || suspended
                        || capturedEpoch != epoch
                        || capturedGeneration != generation) {
                    completed++;
                    break;
                }
            }
        }
        return completed;
    }

    @Override
    public synchronized void onImageAvailable(Runnable value) {
        checkOpen();
        listener = value;
    }

    @Override
    public engine.RenderFailure failure() {
        return failure;
    }

    @Override
    public void onFailure(java.util.function.Consumer<engine.RenderFailure> value) {
        failureListener = value;
        var current = failure;
        if (current != null && value != null) {
            value.accept(current);
        }
    }

    @Override
    public synchronized RenderImage acquireImage() {
        checkOpen();
        if (latest == null) {
            return null;
        }
        latest.readers++;
        return new Lease(latest);
    }

    private Lease lease(RenderImage image) {
        if (!(image instanceof GpuRenderSession.Lease value)
                || value.session() != this
                || value.closed) {
            throw new IllegalArgumentException("Closed or foreign GPU image lease");
        }
        return value;
    }

    @Override
    public synchronized void presented(RenderImage image) {
        checkOpen();
        var shown = lease(image).slot;
        if (shown.captured != measuredPublication) {
            resolution.completed(
                    shown.view.width(),
                    shown.view.height(),
                    (System.nanoTime() - shown.captured)
                            / Math.max(1, shown.stats.samplesPerPixel()));
            measuredPublication = shown.captured;
        }
    }

    @Override
    public synchronized RenderProgress progress(RenderImage image) {
        Slot shown = image == null ? null : lease(image).slot;
        return new RenderProgress(
                generation,
                running ? activeGeneration : -1,
                shown == null ? -1 : shown.generation,
                latest == null || latest.generation != generation ? 0 : latest.samples,
                shown == null ? 0 : System.nanoTime() - shown.captured,
                0,
                cancelled,
                0,
                0,
                running);
    }

    @Override
    public synchronized String status() {
        return failure == null ? diagnostics : failure.toString();
    }

    @Override
    public synchronized void suspend() {
        checkOpen();
        suspended = true;
        historyReset++;
        epoch++;
        resolution.reset();
    }

    @Override
    public synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        epoch++;
        listener = null;
        owner.execute(this::retire);
        owner.shutdown();
    }

    private void retire() {
        try {
            try {
                if (kernel != null) {
                    for (var image : java.util.List.copyOf(displayFrames)) {
                        image.retireOnOwner();
                    }
                    preserveRetainedRawImages();
                }
            } finally {
                if (kernel != null) {
                    kernel.close();
                    kernel = null;
                }
            }
            retired.complete(null);
        } catch (Throwable error) {
            retired.completeExceptionally(error);
        }
    }

    <T> T onOwner(java.util.concurrent.Callable<T> action) {
        var result = new java.util.concurrent.CompletableFuture<T>();
        synchronized (this) {
            checkOpen();
            owner.execute(
                    () -> {
                        try {
                            result.complete(action.call());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        }
        return result.join();
    }

    void releaseDeviceFrame(GpuDevicePixels image) {
        synchronized (this) {
            if (closed) {
                return;
            } // Retirement materializes retained frames and releases storage.
            owner.execute(
                    () -> {
                        displayFrames.remove(image);
                        if (recycledDisplay.size() < 3) {
                            recycledDisplay.addLast(image.snapshot);
                        } else {
                            image.destroyOnOwner();
                        }
                    });
        }
    }

    GpuKernel.Snapshot takeDisplayStorage() {
        return recycledDisplay.isEmpty() ? new GpuKernel.Snapshot() : recycledDisplay.removeFirst();
    }

    float[] readDeviceFrame(GpuDevicePixels image) {
        var result = new java.util.concurrent.CompletableFuture<float[]>();
        synchronized (this) {
            if (closed) {
                retired.whenComplete(
                        (unused, error) -> {
                            if (error != null) {
                                result.completeExceptionally(error);
                            } else {
                                result.complete(image.retiredPixels);
                            }
                        });
            } else {
                owner.execute(
                        () -> {
                            try {
                                result.complete(image.snapshot.read(kernel.gpu));
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        });
            }
        }
        return result.join();
    }

    private engine.DevicePixels deviceCopy(Slot slot) {
        return onOwner(
                () -> {
                    boolean reconstructed = canPresent(slot);
                    var copiedView = reconstructed ? slot.presentationView : slot.view;
                    var image =
                            new GpuDevicePixels(
                                    this,
                                    kernel.gpu,
                                    reconstructed ? slot.presentation : slot.snapshot,
                                    copiedView.width(),
                                    copiedView.height());
                    displayFrames.add(image);
                    return image;
                });
    }

    private void preserveRetainedRawImages() {
        // Outstanding leases remain readable after normal session shutdown.
        for (var slot : slots) {
            boolean retained;
            synchronized (this) {
                retained = slot.readers > 0;
            }
            if (retained && slot.reconstructed && slot.shownPixels == null) {
                slot.shownPixels = slot.presentation.read(kernel.gpu);
            }
            if (retained && slot.snapshot != null && slot.pixels == null) {
                slot.pixels = slot.snapshot.read(kernel.gpu);
            }
        }
    }

    /** Verification waits outside input/display code for native ownership to retire. */
    public boolean awaitClosed(long seconds) throws InterruptedException {
        return owner.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("GPU session closed");
        }
    }

    private synchronized boolean canPresent(Slot slot) {
        return slot.reconstructed
                && GpuTemporal.fallback(view, settings) == null
                && slot.temporalEpoch == temporalEpoch
                && slot.temporalRevision == settings.temporalRevision();
    }

    private float[] shown(Slot slot) {
        if (slot.shownPixels != null) {
            return slot.shownPixels;
        }
        var result = new java.util.concurrent.CompletableFuture<float[]>();
        synchronized (this) {
            if (closed) {
                returnAfterRetirement(slot, result);
            } else {
                owner.execute(
                        () -> {
                            try {
                                if (slot.shownPixels == null) {
                                    slot.shownPixels = slot.presentation.read(kernel.gpu);
                                }
                                result.complete(slot.shownPixels);
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        });
            }
        }
        return result.join();
    }

    private void returnAfterRetirement(
            Slot slot, java.util.concurrent.CompletableFuture<float[]> result) {
        retired.whenComplete(
                (unused, error) -> {
                    if (error == null) {
                        result.complete(slot.shownPixels);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
    }

    private float[] raw(Slot slot) {
        if (slot.pixels != null) {
            return slot.pixels;
        }
        if (failure != null) {
            throw failure;
        }
        var result = new java.util.concurrent.CompletableFuture<float[]>();
        synchronized (this) {
            if (!closed) {
                owner.execute(() -> readRaw(slot, result));
            } else {
                retired.whenComplete(
                        (unused, error) -> {
                            if (error == null) {
                                result.complete(slot.pixels);
                            } else {
                                result.completeExceptionally(error);
                            }
                        });
            }
        }
        return result.join();
    }

    private void readRaw(Slot slot, java.util.concurrent.CompletableFuture<float[]> result) {
        try {
            if (failure != null) {
                throw failure;
            }
            slot.pixels = slot.snapshot.read(kernel.gpu);
            result.complete(slot.pixels);
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    private final class Lease implements RenderImage, RgbPixels {
        private final Slot slot;
        private boolean closed;

        Lease(Slot slot) {
            this.slot = slot;
        }

        GpuRenderSession session() {
            return GpuRenderSession.this;
        }

        private void open() {
            if (closed) {
                throw new IllegalStateException("Image lease closed");
            }
        }

        @Override
        public int width() {
            open();
            return slot.view.width();
        }

        @Override
        public int height() {
            open();
            return slot.view.height();
        }

        @Override
        public long samples() {
            open();
            return slot.samples;
        }

        @Override
        public long generation() {
            open();
            return slot.generation;
        }

        @Override
        public long publicationNanos() {
            open();
            return slot.captured;
        }

        @Override
        public long finishedNanos() {
            open();
            return slot.finished;
        }

        @Override
        public Camera camera() {
            open();
            return slot.view.camera();
        }

        @Override
        public engine.DevicePixels copyDevicePixels() {
            open();
            return devicePresentation && slot.snapshot != null ? deviceCopy(slot) : null;
        }

        @Override
        public engine.Bloom displayBloom() {
            open();
            return slot.bloom;
        }

        @Override
        public int[] takeDisplayPixels(float exposure) {
            synchronized (GpuRenderSession.this) {
                open();
                if (slot.readers != 1
                        || slot.packed == null
                        || slot.exposure != exposure
                        || (slot.reconstructed && !canPresent(slot))) {
                    return null;
                }
                // Detach, never lend. Require the sole lease so another reader cannot be copying
                // this array when ownership changes. Later leases use the immutable raw snapshot.
                int[] pixels = slot.packed;
                slot.packed = null;
                return pixels;
            }
        }

        @Override
        public int displayWidth() {
            open();
            return slot.presentationView.width();
        }

        @Override
        public int displayHeight() {
            open();
            return slot.presentationView.height();
        }

        @Override
        public RgbPixels rawPixels() {
            open();
            return this;
        }

        @Override
        public RgbPixels presentationPixels() {
            open();
            return canPresent(slot) ? presentationReader : this;
        }

        private final RgbPixels presentationReader =
                new RgbPixels() {
                    public int width() {
                        return slot.presentationView.width();
                    }

                    public int height() {
                        return slot.presentationView.height();
                    }

                    public float value(int channel, int x, int y) {
                        open();
                        if (channel < 0
                                || channel >= 3
                                || x < 0
                                || x >= width()
                                || y < 0
                                || y >= height()) {
                            throw new IndexOutOfBoundsException();
                        }
                        return shown(slot)[(y * width() + x) * 3 + channel];
                    }

                    public void copyTo(float[][][] destination) {
                        open();
                        if (destination.length != 3) {
                            throw new IllegalArgumentException("Three channels required");
                        }
                        for (int c = 0; c < 3; c++) {
                            if (destination[c].length != height()) {
                                throw new IllegalArgumentException("Height mismatch");
                            }
                            for (int y = 0; y < height(); y++) {
                                if (destination[c][y].length != width()) {
                                    throw new IllegalArgumentException("Width mismatch");
                                }
                                for (int x = 0; x < width(); x++) {
                                    destination[c][y][x] = value(c, x, y);
                                }
                            }
                        }
                    }

                    public void copyDisplayTo(int[] destination, float exposure) {
                        open();
                        if (destination.length != width() * height()) {
                            throw new IllegalArgumentException("Display destination size mismatch");
                        }
                        if (slot.packed != null
                                && slot.bloom == engine.Bloom.OFF
                                && exposure == slot.exposure) {
                            System.arraycopy(slot.packed, 0, destination, 0, destination.length);
                        } else {
                            engine.DisplayCopy.interleaved(
                                    shown(slot), destination, width(), height(), exposure);
                        }
                    }
                };

        @Override
        public boolean reconstructed() {
            open();
            return canPresent(slot);
        }

        @Override
        public long temporalRevision() {
            open();
            return slot.temporalRevision;
        }

        @Override
        public long cameraHistoryRevision() {
            open();
            return 0;
        }

        @Override
        public TraceProfile.Stats traceStats() {
            open();
            return slot.stats;
        }

        @Override
        public long traceNanos() {
            open();
            return slot.trace;
        }

        @Override
        public int primaryRays() {
            open();
            return width() * height() * slot.stats.samplesPerPixel();
        }

        @Override
        public int primaryHits() {
            open();
            return -1;
        }

        @Override
        public int shadowRays() {
            open();
            return -1;
        }

        @Override
        public int shadowsOccluded() {
            open();
            return -1;
        }

        @Override
        public int litPixels() {
            open();
            return -1;
        }

        @Override
        public int requestedBatch() {
            open();
            return slot.stats.samplesPerPixel();
        }

        @Override
        public int plannedBatch() {
            open();
            return slot.stats.samplesPerPixel();
        }

        @Override
        public boolean motionBudget() {
            open();
            return false;
        }

        @Override
        public String historyLabel() {
            open();
            return slot.reconstructed && !canPresent(slot) ? "raw GPU" : slot.historyLabel;
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            synchronized (GpuRenderSession.this) {
                if (!closed) {
                    slot.readers--;
                    closed = true;
                }
            }
        }

        @Override
        public float value(int channel, int x, int y) {
            open();
            if (channel < 0 || channel >= 3 || x < 0 || x >= width() || y < 0 || y >= height()) {
                throw new IndexOutOfBoundsException();
            }
            return raw(slot)[(y * width() + x) * 3 + channel];
        }

        @Override
        public void copyTo(float[][][] destination) {
            open();
            if (destination.length != 3) {
                throw new IllegalArgumentException("Three channels required");
            }
            for (int c = 0; c < 3; c++) {
                if (destination[c].length != height()) {
                    throw new IllegalArgumentException("Height mismatch");
                }
                for (int y = 0; y < height(); y++) {
                    if (destination[c][y].length != width()) {
                        throw new IllegalArgumentException("Width mismatch");
                    }
                    for (int x = 0; x < width(); x++) {
                        destination[c][y][x] = value(c, x, y);
                    }
                }
            }
        }

        @Override
        public void copyDisplayTo(int[] destination, float exposureMultiplier) {
            open();
            if (destination.length != slot.view.width() * slot.view.height()) {
                throw new IllegalArgumentException("Display destination size mismatch");
            }
            if (!slot.reconstructed
                    && slot.packed != null
                    && slot.bloom == engine.Bloom.OFF
                    && exposureMultiplier == slot.exposure) {
                System.arraycopy(slot.packed, 0, destination, 0, destination.length);
                return;
            }
            // An exposure edit can recolor a converged image without tracing another sample.
            engine.DisplayCopy.interleaved(
                    raw(slot),
                    destination,
                    slot.view.width(),
                    slot.view.height(),
                    exposureMultiplier);
        }
    }
}
