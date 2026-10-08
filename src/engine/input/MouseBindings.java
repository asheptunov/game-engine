package engine.input;

import java.util.function.Consumer;

/** Serializable mouse bindings that deliver normalized pointer input to actions. */
public final class MouseBindings extends BindingSet<MouseChord, Consumer<MouseInput>> {
    public MouseBindings(ActionRegistry<Consumer<MouseInput>> registry) {
        super(registry, MouseChord::parse, MouseChord::format);
    }

    public MouseBindings bind(MouseChord chord, String actionId) {
        super.put(chord, actionId);
        return this;
    }

    public boolean handle(MouseInput input) {
        return fire(MouseChord.from(input), action -> action.accept(input));
    }

    @Override
    public MouseBindings validate(String owner) {
        super.validate(owner);
        return this;
    }
}
