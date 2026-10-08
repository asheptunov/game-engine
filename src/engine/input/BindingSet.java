package engine.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared typed storage, lookup, validation and dispatch for binding layers. */
public abstract class BindingSet<C, A> implements BindingTable {
    private final Map<C, String> bindings = new LinkedHashMap<>();
    protected final ActionRegistry<A> registry;
    private final Function<String, C> parser;
    private final Function<C, String> formatter;

    protected BindingSet(
            ActionRegistry<A> registry, Function<String, C> parser, Function<C, String> formatter) {
        this.registry = java.util.Objects.requireNonNull(registry);
        this.parser = parser;
        this.formatter = formatter;
    }

    protected final void put(C chord, String actionId) {
        ActionRegistry.validateId(actionId);
        bindings.put(java.util.Objects.requireNonNull(chord), actionId);
    }

    public final Optional<String> lookup(C chord) {
        return Optional.ofNullable(bindings.get(chord));
    }

    public final Map<C, String> unresolved() {
        var result = new LinkedHashMap<C, String>();
        bindings.forEach(
                (chord, id) -> {
                    if (registry.get(id).isEmpty()) result.put(chord, id);
                });
        return Collections.unmodifiableMap(result);
    }

    protected final boolean fire(C chord, Consumer<A> invoker) {
        var id = bindings.get(chord);
        if (id == null) return false;
        var action = registry.get(id).orElse(null);
        if (action == null) return false;
        invoker.accept(action);
        return true;
    }

    @Override
    public final void bindParsed(String chord, String actionId) {
        var parsed = parser.apply(chord);
        if (bindings.containsKey(parsed))
            throw new IllegalArgumentException(
                    "Duplicate binding for chord '" + formatter.apply(parsed) + "'");
        put(parsed, actionId);
    }

    @Override
    public final Map<String, String> serialized() {
        var result = new LinkedHashMap<String, String>();
        bindings.forEach((chord, id) -> result.put(formatter.apply(chord), id));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public BindingSet<C, A> validate(String owner) {
        var bad = unresolved();
        if (!bad.isEmpty())
            throw new IllegalStateException(owner + " has unresolved bindings: " + bad);
        return this;
    }
}
