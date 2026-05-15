package ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named-action lookup. Scenes register their actions here under string ids; a {@link Bindings} layer
 * references those ids. The split lets bindings live in pure data (config file or builder) without
 * needing to know about the action implementations. {@code T} is the action type — {@link Runnable}
 * for key actions, {@code Consumer<java.awt.event.MouseEvent>} for mouse actions.
 */
public class ActionRegistry<T> {
    private final Map<String, T> actions = new LinkedHashMap<>();

    public ActionRegistry<T> register(String id, T action) {
        if (actions.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate action id: " + id);
        }
        actions.put(id, action);
        return this;
    }

    public Optional<T> get(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(actions.keySet());
    }
}
