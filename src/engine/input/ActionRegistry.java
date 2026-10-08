package engine.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Named actions referenced by serializable bindings. */
public class ActionRegistry<A> {
    private final Map<String, A> actions = new LinkedHashMap<>();

    public ActionRegistry<A> register(String id, A action) {
        validateId(id);
        Objects.requireNonNull(action, "action");
        if (actions.putIfAbsent(id, action) != null)
            throw new IllegalArgumentException("Duplicate action id: " + id);
        return this;
    }

    public Optional<A> get(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid action id: '" + id + "'");
    }
}
