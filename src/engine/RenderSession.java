package engine;

/** Independent accumulation/history/cancellation context for one view of a world. */
public interface RenderSession extends AutoCloseable {
    void update(WorldSnapshot world, RenderView view, RenderSettings settings);

    /** Re-read an attached legacy adapter or re-evaluate pending input before the next request. */
    void invalidate();

    void request();

    /**
     * Replace the optional completion signal (null detaches). Called on the coordinator outside
     * engine locks after publication. The listener must only enqueue work, must not throw, and must
     * tolerate a signal already in flight when detached or closed. Acquire images through a lease
     * on the session owner; a signal carries no image or ownership.
     */
    void onImageAvailable(Runnable listener);

    RenderImage acquireImage();

    void presented(RenderImage image);

    RenderProgress progress(RenderImage shown);

    String status();

    void suspend();

    boolean closed();

    @Override
    void close();
}
