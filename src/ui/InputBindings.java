package ui;

/**
 * Maps {@link KeyChord}s to action ids registered in an {@link ActionRegistry}. Designed as data —
 * {@link BindingsLoader} populates the same shape from a config file. Use {@link #unresolved()} to
 * sanity-check that every bound id is registered (good as a startup or test assertion).
 */
public class InputBindings extends AbstractBindings<KeyChord, Runnable> {
    public InputBindings(ActionRegistry<Runnable> registry) {
        super(registry);
    }

    public InputBindings bind(KeyChord chord, String actionId) {
        bindings.put(chord, actionId);
        return this;
    }

    @Override
    public void bindParsed(String chordStr, String actionId) {
        bind(KeyChord.parse(chordStr), actionId);
    }

    /** Look up the binding for {@code action} and invoke its registered runnable. Returns true iff fired. */
    public boolean handle(KeyAction action) {
        return fire(KeyChord.from(action), Runnable::run);
    }

    @Override
    public InputBindings validate(String owner) {
        checkResolved(owner);
        return this;
    }
}
