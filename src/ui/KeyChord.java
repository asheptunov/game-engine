package ui;

/**
 * Key + modifier set used as the lookup key in {@link InputBindings}. Strict matching: a chord with no
 * modifiers does <i>not</i> match an event that has Shift held. Define separate chords if needed.
 */
public record KeyChord(KeyAction.Key key, boolean ctrl, boolean alt, boolean shift, boolean meta) {
    public static KeyChord of(KeyAction.Key key) {
        return new KeyChord(key, false, false, false, false);
    }

    public static KeyChord ctrl(KeyAction.Key key) {
        return new KeyChord(key, true, false, false, false);
    }

    public static KeyChord shift(KeyAction.Key key) {
        return new KeyChord(key, false, false, true, false);
    }

    public static KeyChord alt(KeyAction.Key key) {
        return new KeyChord(key, false, true, false, false);
    }

    public static KeyChord ctrlShift(KeyAction.Key key) {
        return new KeyChord(key, true, false, true, false);
    }

    public static KeyChord from(KeyAction action) {
        var m = action.mods();
        return new KeyChord(action.raw(), m.ctrl(), m.alt(), m.shift(), m.meta());
    }

    /**
     * Parses chord syntax like {@code "q"}, {@code "ctrl+a"}, {@code "ctrl+shift+z"}, {@code "f5"}.
     * Modifiers (case-insensitive, any order): {@code ctrl}, {@code shift}, {@code alt}, {@code meta}.
     * The last {@code +}-separated token is the key (see {@link KeyAction.Key#parse}).
     */
    public static KeyChord parse(String chord) {
        var trimmed = chord.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty chord: '" + chord + "'");
        }
        // A trailing '+' is the PLUS key itself, not a token separator.
        String keyToken, modifierPart;
        if (trimmed.endsWith("+")) {
            keyToken = "+";
            modifierPart = trimmed.substring(0, trimmed.length() - 1);
        } else {
            int lastPlus = trimmed.lastIndexOf('+');
            keyToken = trimmed.substring(lastPlus + 1);
            modifierPart = lastPlus < 0 ? "" : trimmed.substring(0, lastPlus);
        }
        boolean ctrl = false, alt = false, shift = false, meta = false;
        for (var mod : modifierPart.split("\\+")) {
            if (mod.isBlank()) continue;
            switch (mod.trim().toLowerCase()) {
                case "ctrl" -> ctrl = true;
                case "shift" -> shift = true;
                case "alt" -> alt = true;
                case "meta" -> meta = true;
                default -> throw new IllegalArgumentException(
                        "Unknown modifier '" + mod + "' in chord '" + chord + "'");
            }
        }
        return new KeyChord(KeyAction.Key.parse(keyToken), ctrl, alt, shift, meta);
    }
}
