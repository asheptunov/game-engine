package engine;

/** Independent accumulation/history/cancellation context for one view of a world. */
public interface RenderSession extends AutoCloseable {
    void update(WorldSnapshot world, RenderView view, RenderSettings settings);
    /** Re-read an attached legacy adapter or re-evaluate pending input before the next request. */
    void invalidate();
    void request();
    RenderImage acquireImage();
    void presented(RenderImage image);
    RenderProgress progress(RenderImage shown);
    String status();
    void suspend();
    boolean closed();
    @Override void close();
}
