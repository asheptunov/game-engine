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
            engine.FoveationQuality foveationQuality) {}

    private static final class Slot {
        volatile float[] pixels;
        int[] packed;
        float exposure;
        boolean deviceOnly;
        GpuKernel.Snapshot snapshot;
        int readers;
        boolean writing;
        RenderView view;
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
                        effectiveQuality(nextSettings));
        synchronized (this) {
            checkOpen();
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
                                && sameSampling(nextSettings, settings);
                if (!cameraOnly) {
                    epoch++;
                }
                key = nextKey;
            }
            if (settings != null
                    && (settings.paused() != nextSettings.paused()
                            || settings.sampleTarget() != nextSettings.sampleTarget()
                            || settings.interactive() != nextSettings.interactive()
                            || !sameSampling(settings, nextSettings))) {
                epoch++;
                resolution.reset();
            }
            world = nextWorld;
            view = nextView;
            settings = nextSettings;
        }
    }

    @Override
    public synchronized void invalidate() {
        checkOpen();
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
        var sampledView = resolution.choose(view, settings, now);
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
                        key.foveationQuality());
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
        destination.deviceOnly = devicePresentation;
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
                                    "GPU %s | %s | execute %.2f ms | transfer %.2f ms | host %.2f"
                                        + " ms | prepare %.2f ms | peak %.1f MiB | primitive"
                                        + " counters n/a | init %.2f ms | max prepare %.2f ms | fog"
                                        + " build %.2f ms | fog builds %d | fog grid 1/%d requested"
                                        + " 1/%d | fog primary %.2f ms | fog reused %d | lighting"
                                        + " grid %b requested %b | grid build %.2f ms | fog jitter"
                                        + " %b | display %s | sampling %s",
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
                                            : destination.packed == null ? "CPU RGB" : "GPU packed",
                                    samplingLabel(capturedSettings));
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

    private static void prepareDestination(Slot destination, RenderView capturedView) {
        int length = capturedView.width() * capturedView.height() * 3;
        if (destination.snapshot != null) {
            destination.pixels = null;
            if (destination.deviceOnly) {
                destination.packed = null;
            } else if (destination.packed == null || destination.packed.length != length / 3) {
                destination.packed = new int[length / 3];
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
                    destination.pixels,
                    destination.packed,
                    destination.snapshot,
                    destination.exposure);
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
                    var image =
                            new GpuDevicePixels(
                                    this,
                                    kernel.gpu,
                                    slot.snapshot,
                                    slot.view.width(),
                                    slot.view.height());
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
        public int[] takeDisplayPixels(float exposure) {
            synchronized (GpuRenderSession.this) {
                open();
                if (slot.readers != 1 || slot.packed == null || slot.exposure != exposure) {
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
        public RgbPixels rawPixels() {
            open();
            return this;
        }

        @Override
        public RgbPixels presentationPixels() {
            open();
            return this;
        }

        @Override
        public boolean reconstructed() {
            open();
            return false;
        }

        @Override
        public long temporalRevision() {
            open();
            return 0;
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
            return "raw GPU";
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
            if (slot.packed != null && exposureMultiplier == slot.exposure) {
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
