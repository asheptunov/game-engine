package scenes;

public interface Scene {
    /** Clear transient held input when a scene is hidden or loses focus. */
    default void suspendInput() {}
}
