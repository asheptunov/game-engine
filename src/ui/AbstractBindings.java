package ui;

import logging.LogManager;
import logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shared storage and consistency checks for {@link Bindings} implementations. Subclasses add a typed
 * {@code bind}/{@code handle} surface over the {@code chord → action id} map.
 *
 * @param <C> the chord type used as the lookup key ({@link KeyChord} or {@link MouseChord})
 * @param <A> the action type stored in the registry ({@link Runnable} or a {@code Consumer})
 */
public abstract class AbstractBindings<C, A> implements Bindings {
    private static final Logger LOG = LogManager.instance().getThis();

    protected final Map<C, String>    bindings = new LinkedHashMap<>();
    protected final ActionRegistry<A> registry;

    protected AbstractBindings(ActionRegistry<A> registry) {
        this.registry = registry;
    }

    public Optional<String> lookup(C chord) {
        return Optional.ofNullable(bindings.get(chord));
    }

    /** Bindings whose action id isn't in the registry. Empty when wiring is consistent. */
    public Map<C, String> unresolved() {
        var bad = new LinkedHashMap<C, String>();
        bindings.forEach((chord, id) -> {
            if (registry.get(id).isEmpty()) {
                bad.put(chord, id);
            }
        });
        return Collections.unmodifiableMap(bad);
    }

    protected void checkResolved(String owner) {
        var bad = unresolved();
        if (!bad.isEmpty()) {
            throw new IllegalStateException(owner + " has unresolved bindings: " + bad);
        }
    }

    /**
     * Look up the action bound to {@code chord} and pass it to {@code invoker}. Returns true iff a
     * registered action was found and invoked. Subclasses use this from {@code handle} — the only
     * difference between key and mouse dispatch is how the action is called once located.
     */
    protected boolean fire(C chord, Consumer<A> invoker) {
        var id = bindings.get(chord);
        if (id == null) {
            return false;
        }
        var fn = registry.get(id).orElse(null);
        if (fn == null) {
            LOG.warn("Action id '%s' is bound but not registered", id);
            return false;
        }
        invoker.accept(fn);
        return true;
    }
}
