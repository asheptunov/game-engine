package ui;

/**
 * Common surface for {@link InputBindings} (keyboard) and {@link MouseBindings} (mouse). Exposes just
 * enough for {@link BindingsLoader} to populate and validate a binding layer without knowing its
 * concrete chord type.
 */
public interface Bindings {
    /** Parses {@code chordStr} into this layer's chord type and binds it to {@code actionId}. */
    void bindParsed(String chordStr, String actionId);

    /** Startup wiring check. Throws if any bound chord references an unregistered action id. */
    Bindings validate(String owner);
}
