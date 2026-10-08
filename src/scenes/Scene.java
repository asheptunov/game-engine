package scenes;

public interface Scene {
    /** Clear transient held input when a scene is hidden or loses focus. */
    default void suspendInput() {}

    /** Resume active scene work after scene activation or window focus gain. */
    default void resumeInput() {}

    default void windowFocus(boolean focused) {
        if (focused) resumeInput();
        else suspendInput();
    }

    default boolean windowFocused() {
        return true;
    }
}
