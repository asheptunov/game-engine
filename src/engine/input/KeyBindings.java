package engine.input;

/** Serializable physical-key bindings whose actions need no event payload. */
public final class KeyBindings extends BindingSet<KeyChord,Runnable> {
    public KeyBindings(ActionRegistry<Runnable> registry){super(registry,KeyChord::parse,KeyChord::format);}
    public KeyBindings bind(KeyChord chord,String actionId){super.put(chord,actionId);return this;}
    public boolean handle(KeyInput input){
        return input.pressed()&&input.key()!=KeyCode.UNKNOWN&&fire(KeyChord.from(input),Runnable::run);
    }
    @Override public KeyBindings validate(String owner){super.validate(owner);return this;}
}
