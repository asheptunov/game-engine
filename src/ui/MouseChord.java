package ui;

import java.awt.event.MouseEvent;

/**
 * Mouse button + gesture + modifier set + scene mode, used as the lookup key in {@link MouseBindings}.
 * The {@code mode} string lets a scene bind different actions to the same gesture per mode (e.g. a
 * left-press is "paint" in brush mode but "fill" in fill mode); {@code ""} matches mode-agnostically.
 * Strict matching, like {@link KeyChord}. {@code mode} is normalised to lower case.
 */
public record MouseChord(MouseButton button,
                         MouseGesture gesture,
                         String mode,
                         boolean ctrl, boolean alt, boolean shift, boolean meta) {
    public MouseChord {
        mode = mode == null ? "" : mode.toLowerCase();
    }

    public static MouseChord of(MouseButton button, MouseGesture gesture, String mode) {
        return new MouseChord(button, gesture, mode, false, false, false, false);
    }

    /** Builds the chord for an AWT event seen as {@code gesture} while the scene is in {@code mode}. */
    public static MouseChord from(MouseGesture gesture, MouseEvent e, String mode) {
        return new MouseChord(MouseButton.fromAwt(e), gesture, mode,
                e.isControlDown(), e.isAltDown(), e.isShiftDown(), e.isMetaDown());
    }

    /**
     * Parses chord syntax like {@code "left+press+brush"}, {@code "drag+box_select"}, {@code "wheel"}.
     * Tokens are {@code +}-separated, case-insensitive, order-independent:
     * <ul>
     *   <li>gesture (required): {@code press}, {@code release}, {@code drag}, {@code wheel}</li>
     *   <li>button (optional, default {@code none}): {@code left}, {@code middle}, {@code right}, {@code none}</li>
     *   <li>modifiers (optional): {@code ctrl}, {@code shift}, {@code alt}, {@code meta}</li>
     *   <li>mode (optional): any other single token, matched against the scene's current mode</li>
     * </ul>
     */
    public static MouseChord parse(String chord) {
        var trimmed = chord.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty mouse chord: '" + chord + "'");
        }
        MouseButton  button  = MouseButton.NONE;
        MouseGesture gesture = null;
        String       mode    = "";
        boolean ctrl = false, alt = false, shift = false, meta = false;
        for (var token : trimmed.split("\\+")) {
            var t = token.trim().toLowerCase();
            if (t.isEmpty()) {
                continue;
            }
            switch (t) {
                case "ctrl" -> ctrl = true;
                case "shift" -> shift = true;
                case "alt" -> alt = true;
                case "meta" -> meta = true;
                case "left" -> button = MouseButton.LEFT;
                case "middle" -> button = MouseButton.MIDDLE;
                case "right" -> button = MouseButton.RIGHT;
                case "none" -> button = MouseButton.NONE;
                case "press" -> gesture = MouseGesture.PRESS;
                case "release" -> gesture = MouseGesture.RELEASE;
                case "drag" -> gesture = MouseGesture.DRAG;
                case "wheel" -> gesture = MouseGesture.WHEEL;
                default -> {
                    if (!mode.isEmpty()) {
                        throw new IllegalArgumentException("Multiple mode tokens in mouse chord '"
                                + chord + "': '" + mode + "' and '" + t + "'");
                    }
                    mode = t;
                }
            }
        }
        if (gesture == null) {
            throw new IllegalArgumentException("No gesture in mouse chord '" + chord
                    + "' (expected one of press, release, drag, wheel)");
        }
        return new MouseChord(button, gesture, mode, ctrl, alt, shift, meta);
    }
}
