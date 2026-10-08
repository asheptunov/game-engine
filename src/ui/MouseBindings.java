package ui;

import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Maps {@link MouseChord}s to action ids registered in an {@link ActionRegistry}. The mouse
 * analogue of {@link InputBindings}: actions are {@code Consumer<MouseEvent>} (they need the cursor
 * position), and {@link #handle} takes the gesture plus the scene's current mode to build the
 * lookup chord.
 */
public class MouseBindings
        extends engine.input.BindingSet<engine.input.MouseChord, Consumer<MouseEvent>>
        implements Bindings {
    public MouseBindings(ActionRegistry<Consumer<MouseEvent>> registry) {
        super(registry, engine.input.MouseChord::parse, engine.input.MouseChord::format);
    }

    public MouseBindings bind(MouseChord chord, String actionId) {
        super.put(chord.toEngine(), actionId);
        return this;
    }

    /**
     * Look up the binding for {@code e}, seen as {@code gesture} while the scene is in {@code
     * mode}, and invoke its registered consumer. Returns true iff an action fired.
     */
    public boolean handle(MouseGesture gesture, MouseEvent e, String mode) {
        return fire(MouseChord.from(gesture, e, mode).toEngine(), fn -> fn.accept(e));
    }

    public java.util.Optional<String> lookup(MouseChord chord) {
        return super.lookup(chord.toEngine());
    }

    @Override
    public MouseBindings validate(String owner) {
        super.validate(owner);
        return this;
    }
}
