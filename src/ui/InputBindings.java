package ui;

/**
 * Maps {@link KeyChord}s to action ids registered in an {@link ActionRegistry}. Designed as data —
 * {@link BindingsLoader} populates the same shape from a config file. Use {@link #unresolved()} to
 * sanity-check that every bound id is registered (good as a startup or test assertion).
 */
public class InputBindings extends engine.input.BindingSet<engine.input.KeyChord,Runnable> implements Bindings {
    public InputBindings(ActionRegistry<Runnable> registry) {
        super(registry,engine.input.KeyChord::parse,engine.input.KeyChord::format);
    }

    public InputBindings bind(KeyChord chord, String actionId) {
        super.put(chord.toEngine(),actionId);
        return this;
    }

    /** Look up the binding for {@code action} and invoke its registered runnable. Returns true iff fired. */
    public boolean handle(KeyAction action) {
        var input=new engine.input.KeyInput(KeyChord.physical(action.raw()),
                action.action()==KeyAction.Action.PRESS,
                new engine.input.Modifiers(action.mods().ctrl(),action.mods().alt(),action.mods().shift(),action.mods().meta()));
        return input.pressed()&&fire(engine.input.KeyChord.from(input),Runnable::run);
    }

    public java.util.Optional<String> lookup(KeyChord chord){return super.lookup(chord.toEngine());}

    @Override
    public InputBindings validate(String owner) {
        super.validate(owner);
        return this;
    }
}
