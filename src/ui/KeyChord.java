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

    engine.input.KeyChord toEngine() {
        return new engine.input.KeyChord(physical(key),
                new engine.input.Modifiers(ctrl, alt, shift || impliedShift(key), meta));
    }

    static engine.input.KeyCode physical(KeyAction.Key key) {
        return switch (key) {
            case TILDE -> engine.input.KeyCode.GRAVE;
            case BANG -> engine.input.KeyCode.ONE;
            case AT -> engine.input.KeyCode.TWO;
            case HASH -> engine.input.KeyCode.THREE;
            case DOLLAR -> engine.input.KeyCode.FOUR;
            case PERCENT -> engine.input.KeyCode.FIVE;
            case CARET -> engine.input.KeyCode.SIX;
            case AMPERSAND -> engine.input.KeyCode.SEVEN;
            case ASTERISK -> engine.input.KeyCode.EIGHT;
            case L_PAREN -> engine.input.KeyCode.NINE;
            case R_PAREN -> engine.input.KeyCode.ZERO;
            case UNDERSCORE -> engine.input.KeyCode.MINUS;
            case PLUS -> engine.input.KeyCode.EQUAL;
            case L_BRACE -> engine.input.KeyCode.L_BRACKET;
            case R_BRACE -> engine.input.KeyCode.R_BRACKET;
            case PIPE -> engine.input.KeyCode.BACKSLASH;
            case COLON -> engine.input.KeyCode.SEMICOLON;
            case DOUBLE_QUOTE -> engine.input.KeyCode.SINGLE_QUOTE;
            case LESS -> engine.input.KeyCode.COMMA;
            case GREATER -> engine.input.KeyCode.PERIOD;
            case QUESTION -> engine.input.KeyCode.FORWARD_SLASH;
            default -> {
                var name=key.name();
                if(name.startsWith("LOWER_") || name.startsWith("UPPER_"))name=name.substring(6);
                yield engine.input.KeyCode.valueOf(name);
            }
        };
    }

    private static boolean impliedShift(KeyAction.Key key) {
        return key.name().startsWith("UPPER_") || switch (key) {
            case TILDE, BANG, AT, HASH, DOLLAR, PERCENT, CARET, AMPERSAND, ASTERISK,
                    L_PAREN, R_PAREN, UNDERSCORE, PLUS, L_BRACE, R_BRACE, PIPE,
                    COLON, DOUBLE_QUOTE, LESS, GREATER, QUESTION -> true;
            default -> false;
        };
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
