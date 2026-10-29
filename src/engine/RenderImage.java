package engine;

import profiling.TraceProfile;

/**
 * A read-only, single-owner image lease. Use the lease and its derived {@link RgbPixels} on one
 * thread; reads or copies must not overlap {@link #close()}. Pixel storage is valid only until the
 * lease closes.
 */
public interface RenderImage extends AutoCloseable {
    /** Independent owned device snapshot, or null when this backend uses host presentation. */
    default DevicePixels copyDevicePixels() {
        return null;
    }

    /**
     * Transfer an independent, caller-owned packed display array in top-down 0x00RRGGBB order. Its
     * dimensions match presentationPixels(). The caller may modify or retain it after lease close.
     * Raw pixels, samples and other leases remain unchanged. Returns null when unavailable; callers
     * then use presentationPixels().copyDisplayTo(). Invoke on the lease's owner thread.
     */
    default int[] takeDisplayPixels(float exposure) {
        return null;
    }

    /** Bloom mode baked into an optional packed display cache; raw radiance is unchanged. */
    default Bloom displayBloom() {
        return Bloom.OFF;
    }

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

    @Override
    void close();
}
