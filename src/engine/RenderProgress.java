package engine;

/** Immutable asynchronous-session diagnostics. */
public record RenderProgress(long requestedGeneration, long activeGeneration, long shownGeneration, long samples,
                             long shownAgeNanos, long firstImageNanos, long cancelledJobs,
                             long wastedPaths, long maximumTileNanos, boolean running) {}
