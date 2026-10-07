package ui;

/**
 * Named-action lookup. Scenes register their actions here under string ids; a {@link Bindings} layer
 * references those ids. The split lets bindings live in pure data (config file or builder) without
 * needing to know about the action implementations. {@code T} is the action type — {@link Runnable}
 * for key actions, {@code Consumer<java.awt.event.MouseEvent>} for mouse actions.
 */
public class ActionRegistry<T> extends engine.input.ActionRegistry<T> {
    @Override public ActionRegistry<T> register(String id,T action){super.register(id,action);return this;}
}
