package engine.input;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Strict mouse button, gesture, mode, modifier and held-key chord. */
public record MouseChord(
        MouseButton button,
        MouseGesture gesture,
        String mode,
        Modifiers modifiers,
        Set<KeyCode> heldKeys) {
    public MouseChord {
        Objects.requireNonNull(button);
        Objects.requireNonNull(gesture);
        Objects.requireNonNull(modifiers);
        Objects.requireNonNull(heldKeys);
        mode = normalizeMode(mode);
        var copy = heldKeys.isEmpty() ? EnumSet.noneOf(KeyCode.class) : EnumSet.copyOf(heldKeys);
        for (var key : copy)
            if (isModifier(key) || key == KeyCode.UNKNOWN)
                throw new IllegalArgumentException(
                        "Held mouse keys must be non-modifier physical keys");
        heldKeys = Set.copyOf(copy);
    }

    public MouseChord(MouseButton button, MouseGesture gesture, String mode, Modifiers modifiers) {
        this(button, gesture, mode, modifiers, Set.of());
    }

    public static MouseChord of(MouseButton button, MouseGesture gesture, String mode) {
        return new MouseChord(button, gesture, mode, Modifiers.NONE, Set.of());
    }

    public static MouseChord from(MouseInput input) {
        return new MouseChord(
                input.button(), input.gesture(), input.mode(), input.modifiers(), input.heldKeys());
    }

    public static MouseChord parse(String text) {
        var value = text.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty mouse chord");
        MouseButton button = null;
        MouseGesture gesture = null;
        String mode = "";
        boolean bareSpace = false;
        boolean ctrl = false, alt = false, shift = false, meta = false;
        var held = EnumSet.noneOf(KeyCode.class);
        for (var raw : value.split("\\+")) {
            var token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            switch (token) {
                case "ctrl" -> ctrl = true;
                case "alt" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> meta = true;
                case "left" -> button = uniqueButton(button, MouseButton.LEFT, text);
                case "middle" -> button = uniqueButton(button, MouseButton.MIDDLE, text);
                case "right" -> button = uniqueButton(button, MouseButton.RIGHT, text);
                case "none" -> button = uniqueButton(button, MouseButton.NONE, text);
                case "press" -> gesture = uniqueGesture(gesture, MouseGesture.PRESS, text);
                case "release" -> gesture = uniqueGesture(gesture, MouseGesture.RELEASE, text);
                case "drag" -> gesture = uniqueGesture(gesture, MouseGesture.DRAG, text);
                case "move" -> gesture = uniqueGesture(gesture, MouseGesture.MOVE, text);
                case "wheel" -> gesture = uniqueGesture(gesture, MouseGesture.WHEEL, text);
                case "space" -> bareSpace = true;
                default -> {
                    if (token.startsWith("key.")) {
                        var key = KeyCode.parse(token.substring(4));
                        if (isModifier(key))
                            throw new IllegalArgumentException(
                                    "Use modifier syntax instead of held key '" + token + "'");
                        held.add(key);
                    } else {
                        if (!mode.isEmpty())
                            throw new IllegalArgumentException(
                                    "Multiple modes in mouse chord '" + text + "'");
                        mode = token;
                    }
                }
            }
        }
        if (bareSpace) {
            if (mode.isEmpty()) mode = "space";
            else held.add(KeyCode.SPACE);
        }
        if (gesture == null)
            throw new IllegalArgumentException("No gesture in mouse chord '" + text + "'");
        return new MouseChord(
                button == null ? MouseButton.NONE : button,
                gesture,
                mode,
                new Modifiers(ctrl, alt, shift, meta),
                held);
    }

    public String format() {
        var tokens = new ArrayList<String>();
        heldKeys.stream().sorted().forEach(key -> tokens.add("key." + key.token()));
        if (modifiers.ctrl()) tokens.add("ctrl");
        if (modifiers.alt()) tokens.add("alt");
        if (modifiers.shift()) tokens.add("shift");
        if (modifiers.meta()) tokens.add("meta");
        if (button != MouseButton.NONE) tokens.add(button.name().toLowerCase(Locale.ROOT));
        tokens.add(gesture.name().toLowerCase(Locale.ROOT));
        if (!mode.isEmpty()) tokens.add(mode);
        return String.join("+", tokens);
    }

    private static MouseButton uniqueButton(MouseButton current, MouseButton next, String text) {
        if (current != null && current != next)
            throw new IllegalArgumentException("Multiple buttons in mouse chord '" + text + "'");
        return next;
    }

    private static MouseGesture uniqueGesture(
            MouseGesture current, MouseGesture next, String text) {
        if (current != null && current != next)
            throw new IllegalArgumentException("Multiple gestures in mouse chord '" + text + "'");
        return next;
    }

    private static boolean isReserved(String mode) {
        return switch (mode) {
            case "ctrl",
                            "alt",
                            "shift",
                            "meta",
                            "left",
                            "middle",
                            "right",
                            "none",
                            "press",
                            "release",
                            "drag",
                            "move",
                            "wheel" ->
                    true;
            default -> mode.startsWith("key.");
        };
    }

    private static boolean isModifier(KeyCode key) {
        return switch (key) {
            case L_SHIFT, R_SHIFT, L_CTRL, R_CTRL, L_ALT, R_ALT, L_META, R_META, L_WIN, R_WIN ->
                    true;
            default -> false;
        };
    }

    static String normalizeMode(String value) {
        var mode = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!mode.isEmpty() && (!mode.matches("[a-z0-9_.-]+") || isReserved(mode)))
            throw new IllegalArgumentException("Invalid mouse mode '" + mode + "'");
        return mode;
    }
}
