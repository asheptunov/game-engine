package engine;

import profiling.TraceProfile;

/**
 * A read-only, single-owner image lease. Use the lease and its derived {@link RgbPixels}
 * on one thread; reads or copies must not overlap {@link #close()}.
 * Pixel storage is valid only until the lease closes.
 */
public interface RenderImage extends AutoCloseable {
    int width();
    int height();
    long samples();
    long generation();
    long publicationNanos();
    long finishedNanos();
    Camera camera();
    RgbPixels rawPixels();
    RgbPixels presentationPixels();
    boolean reconstructed();
    long temporalRevision();
    long cameraHistoryRevision();
    TraceProfile.Stats traceStats();
    long traceNanos();
    int primaryRays();
    int primaryHits();
    int shadowRays();
    int shadowsOccluded();
    int litPixels();
    int requestedBatch();
    int plannedBatch();
    boolean motionBudget();
    String historyLabel();
    boolean closed();
    @Override void close();
}
