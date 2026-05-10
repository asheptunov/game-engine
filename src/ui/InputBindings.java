package ui;

import logging.LogManager;
import logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps {@link KeyChord}s to action ids registered in an {@link ActionRegistry}. Designed as data — a
 * future config-file loader populates the same shape via {@link #bind}. Use {@link #unresolved()} to
 * sanity-check that every bound id is registered (good as a startup or test assertion).
 */
public class InputBindings {
    private static final Logger LOG = LogManager.instance().getThis();

    private final Map<KeyChord, String> bindings = new LinkedHashMap<>();
    private final ActionRegistry        registry;

    public InputBindings(ActionRegistry registry) {
        this.registry = registry;
    }

    public InputBindings bind(KeyChord chord, String actionId) {
        bindings.put(chord, actionId);
        return this;
    }

    public Optional<String> lookup(KeyChord chord) {
        return Optional.ofNullable(bindings.get(chord));
    }

    /** Look up the binding for {@code action} and invoke its registered runnable. Returns true iff fired. */
    public boolean handle(KeyAction action) {
        var id = bindings.get(KeyChord.from(action));
        if (id == null) {
            return false;
        }
        var fn = registry.get(id).orElse(null);
        if (fn == null) {
            LOG.warn("Action id '%s' is bound but not registered", id);
            return false;
        }
        fn.run();
        return true;
    }

    /** Bindings whose action id isn't in the registry. Empty when wiring is consistent. */
    public Map<KeyChord, String> unresolved() {
        var bad = new LinkedHashMap<KeyChord, String>();
        bindings.forEach((chord, id) -> {
            if (registry.get(id).isEmpty()) {
                bad.put(chord, id);
            }
        });
        return Collections.unmodifiableMap(bad);
    }
}
