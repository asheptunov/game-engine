package engine;

/** Independent accumulation/history/cancellation context for one view of a world. */
public interface RenderSession extends AutoCloseable {
    void update(WorldSnapshot world, RenderView view, RenderSettings settings);

    /** Re-read an attached legacy adapter or re-evaluate pending input before the next request. */
    void invalidate();

    void request();

    /**
     * Runtime availability for an optional filter; never initializes a device or native library.
     */
    default RenderCapabilities.Support denoiserSupport(Denoiser denoiser) {
        return new RenderCapabilities.Support(
                denoiser == Denoiser.BUILT_IN,
                denoiser == Denoiser.BUILT_IN
                        ? "Built-in filter available"
                        : "NRD requires GPU rendering");
    }

    /** Runtime availability without creating a device. */
    default RenderCapabilities.Support upscalerSupport(Upscaler upscaler) {
        return new RenderCapabilities.Support(
                upscaler == Upscaler.OFF,
                upscaler == Upscaler.OFF ? "Upscaling off" : "FSR requires GPU rendering");
    }

    /** Prefer device-resident publications; unsupported backends reject enabling it. */
    default void devicePresentation(boolean enabled) {
        if (enabled) {
            throw new UnsupportedOperationException("Device presentation unavailable");
        }
    }

    /** Optional display hint; changes neither raw accumulation nor sample targets. */
    default void displayExposure(float multiplier) {}

    /**
     * Replace the optional completion signal (null detaches). Called on the coordinator outside
     * engine locks after publication. The listener must only enqueue work, must not throw, and must
     * tolerate a signal already in flight when detached or closed. Acquire images through a lease
     * on the session owner; a signal carries no image or ownership.
     */
    void onImageAvailable(Runnable listener);

    /** First fatal error, or null. Must remain readable without waiting for a worker/device. */
    RenderFailure failure();

    /**
     * Replace the fatal notification listener (null detaches). May be called on any thread,
     * including immediately for an existing failure. Listener must enqueue work and not throw.
     * Repeated delivery is possible; the first failure remains authoritative.
     */
    void onFailure(java.util.function.Consumer<RenderFailure> listener);

    RenderImage acquireImage();

    void presented(RenderImage image);

    RenderProgress progress(RenderImage shown);

    String status();

    void suspend();

    boolean closed();

    @Override
    void close();
}
