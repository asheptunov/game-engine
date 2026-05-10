package ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named-action lookup. Scenes register their actions here under string ids; {@link InputBindings}
 * references those ids. The split lets bindings live in pure data (config file or builder) without
 * needing to know about the action implementations.
 */
public class ActionRegistry {
    private final Map<String, Runnable> actions = new LinkedHashMap<>();

    public ActionRegistry register(String id, Runnable action) {
        if (actions.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate action id: " + id);
        }
        actions.put(id, action);
        return this;
    }

    public Optional<Runnable> get(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(actions.keySet());
    }
}
