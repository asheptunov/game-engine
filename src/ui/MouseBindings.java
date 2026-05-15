package ui;

import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Maps {@link MouseChord}s to action ids registered in an {@link ActionRegistry}. The mouse analogue
 * of {@link InputBindings}: actions are {@code Consumer<MouseEvent>} (they need the cursor position),
 * and {@link #handle} takes the gesture plus the scene's current mode to build the lookup chord.
 */
public class MouseBindings extends AbstractBindings<MouseChord, Consumer<MouseEvent>> {
    public MouseBindings(ActionRegistry<Consumer<MouseEvent>> registry) {
        super(registry);
    }

    public MouseBindings bind(MouseChord chord, String actionId) {
        bindings.put(chord, actionId);
        return this;
    }

    @Override
    public void bindParsed(String chordStr, String actionId) {
        bind(MouseChord.parse(chordStr), actionId);
    }

    /**
     * Look up the binding for {@code e}, seen as {@code gesture} while the scene is in {@code mode},
     * and invoke its registered consumer. Returns true iff an action fired.
     */
    public boolean handle(MouseGesture gesture, MouseEvent e, String mode) {
        return fire(MouseChord.from(gesture, e, mode), fn -> fn.accept(e));
    }

    @Override
    public MouseBindings validate(String owner) {
        checkResolved(owner);
        return this;
    }
}
