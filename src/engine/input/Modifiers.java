package engine.input;

/** Exact aggregate modifier state used by platform-neutral input chords. */
public record Modifiers(boolean ctrl, boolean alt, boolean shift, boolean meta) {
    public static final Modifiers NONE = new Modifiers(false,false,false,false);
}
