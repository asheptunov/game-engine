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
}
