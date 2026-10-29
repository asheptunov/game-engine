package engine;

/** Immutable transport and scheduling input. Presentation exposure is intentionally separate. */
public record RenderSettings(
        int pathDepth,
        long seed,
        long restartRevision,
        int samplesPerBatch,
        long sampleTarget,
        boolean paused,
        boolean acceleration,
        int workers,
        int tileSize,
        boolean temporal,
        long temporalRevision,
        boolean interactive,
        double interactiveMillis,
        int minimumWidth,
        int minimumHeight,
        boolean temporalBudget,
        int motionSamples,
        double motionScale,
        int fogDownsample,
        boolean fogLightingGrid,
        boolean fogJitter,
        boolean foveated,
        boolean smoothFoveation,
        FoveationQuality foveationQuality,
        Bloom bloom) {
    /** Source compatibility for callers predating presentation bloom. */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public RenderSettings(
            int pathDepth,
            long seed,
            long restartRevision,
            int samplesPerBatch,
            long sampleTarget,
            boolean paused,
            boolean acceleration,
            int workers,
            int tileSize,
            boolean temporal,
            long temporalRevision,
            boolean interactive,
            double interactiveMillis,
            int minimumWidth,
            int minimumHeight,
            boolean temporalBudget,
            int motionSamples,
            double motionScale,
            int fogDownsample,
            boolean fogLightingGrid,
            boolean fogJitter,
            boolean foveated,
            boolean smoothFoveation,
            FoveationQuality foveationQuality) {
        this(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                Bloom.OFF);
    }

    public RenderSettings withBloom(Bloom value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                value);
    }

    public RenderSettings {
        java.util.Objects.requireNonNull(bloom, "Bloom mode");
        java.util.Objects.requireNonNull(foveationQuality, "Foveation quality");
        if (fogDownsample != 1 && fogDownsample != 2 && fogDownsample != 4) {
            throw new IllegalArgumentException("Fog downsample must be 1, 2 or 4");
        }
        if (pathDepth < 0 || pathDepth > 32)
            throw new IllegalArgumentException("Depth must be 0..32");
        if (restartRevision < 0
                || temporalRevision < 0
                || temporalRevision > (Long.MAX_VALUE - 1) / 2)
            throw new IllegalArgumentException("Revisions must be nonnegative and representable");
        if (samplesPerBatch < 1 || samplesPerBatch > 8)
            throw new IllegalArgumentException("Samples per batch must be 1..8");
        if (sampleTarget < 0 || sampleTarget > ViewportState.SAMPLE_LIMIT)
            throw new IllegalArgumentException("Sample target must be 0..1000000000");
        int maximumWorkers = Math.min(32, Runtime.getRuntime().availableProcessors());
        if (workers < 1 || workers > maximumWorkers)
            throw new IllegalArgumentException("Workers must be 1.." + maximumWorkers);
        if (tileSize < 1 || tileSize > 256)
            throw new IllegalArgumentException("Tile size must be 1..256");
        if (!Double.isFinite(interactiveMillis)
                || interactiveMillis < 1
                || interactiveMillis > 1000)
            throw new IllegalArgumentException("Interactive target must be 1..1000 milliseconds");
        boolean defaultMinimum = minimumWidth == 0 && minimumHeight == 0;
        if (!defaultMinimum
                && (minimumWidth < 64
                        || minimumWidth > RenderView.MAX_DIMENSION
                        || minimumHeight < 64
                        || minimumHeight > RenderView.MAX_DIMENSION)) {
            throw new IllegalArgumentException(
                    "Minimum dimensions must both be 0 or both be 64.." + RenderView.MAX_DIMENSION);
        }
        if (motionSamples < 1 || motionSamples > 8)
            throw new IllegalArgumentException("Motion samples must be 1..8");
        if (!Double.isFinite(motionScale) || motionScale < .25 || motionScale > 1)
            throw new IllegalArgumentException("Motion scale must be 0.25..1");
    }

    public static RenderSettings defaults() {
        return new RenderSettings(
                0,
                1,
                0,
                1,
                1,
                false,
                true,
                Math.min(14, Math.max(1, Runtime.getRuntime().availableProcessors() - 2)),
                32,
                false,
                0,
                false,
                1000. / 60,
                0,
                0,
                false,
                1,
                1,
                1,
                false,
                false,
                false,
                false,
                FoveationQuality.defaults(),
                Bloom.OFF);
    }

    public RenderSettings withPathDepth(int value) {
        return new RenderSettings(
                value,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withSeed(long value) {
        return new RenderSettings(
                pathDepth,
                value,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withRestartRevision(long value) {
        return new RenderSettings(
                pathDepth,
                seed,
                value,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withSamplesPerBatch(int value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                value,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withSampleTarget(long value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                value,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withPaused(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                value,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withWorkers(int value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                value,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withTileSize(int value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                value,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withAcceleration(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                value,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withTemporal(boolean value, long revision) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                value,
                revision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withInteractive(
            boolean value, double milliseconds, int minimumWidth, int minimumHeight) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                value,
                milliseconds,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    public RenderSettings withTemporalBudget(boolean value, int samples, double scale) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                value,
                samples,
                scale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    /** GPU primary-ray fog quality; CPU and unsupported views retain direct integration. */
    public RenderSettings withFogDownsample(int value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                value,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    /** Optional GPU world-space lighting coefficients; CPU retains direct evaluation. */
    public RenderSettings withFogLightingGrid(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                value,
                fogJitter,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    /** GPU stratified fog sampling; CPU retains its reference integration. */
    public RenderSettings withFogJitter(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                value,
                foveated,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    /** GPU center-weighted spatial sampling; CPU retains uniform sampling. */
    public RenderSettings withFoveated(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                value,
                smoothFoveation,
                foveationQuality,
                bloom);
    }

    /** Smooth GPU reconstruction, active only when center detail is enabled. */
    public RenderSettings withSmoothFoveation(boolean value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                value,
                foveationQuality,
                bloom);
    }

    /** Live smooth sampling controls; ignored by uniform and discrete sampling. */
    public RenderSettings withFoveationQuality(FoveationQuality value) {
        return new RenderSettings(
                pathDepth,
                seed,
                restartRevision,
                samplesPerBatch,
                sampleTarget,
                paused,
                acceleration,
                workers,
                tileSize,
                temporal,
                temporalRevision,
                interactive,
                interactiveMillis,
                minimumWidth,
                minimumHeight,
                temporalBudget,
                motionSamples,
                motionScale,
                fogDownsample,
                fogLightingGrid,
                fogJitter,
                foveated,
                smoothFoveation,
                value,
                bloom);
    }
}
