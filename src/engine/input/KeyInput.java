package engine.input;

import java.util.Objects;

/** One physical key transition, independent of character layout and lock-key state. */
public record KeyInput(KeyCode key, boolean pressed, Modifiers modifiers) {
    public KeyInput {
        Objects.requireNonNull(key);
        Objects.requireNonNull(modifiers);
    }

    public static KeyInput press(KeyCode key, Modifiers modifiers) {
        return new KeyInput(key, true, modifiers);
    }
}
