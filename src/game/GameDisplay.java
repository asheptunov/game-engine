package game;

/**
 * Optional native surface. Submission retains the frame until asynchronous presentation completes.
 */
public interface GameDisplay extends AutoCloseable {
    java.awt.Component component();

    boolean ready();

    void submit(
            GameRenderer.Frame frame,
            int width,
            int height,
            int[] overlay,
            java.util.function.Consumer<GameRenderer.Frame> presented);

    @Override
    void close();
}
