package di;

import java.util.Map;
import java.util.Optional;

public class GraphImpl implements Graph {
    private final Map<Key<?>, Node<?>> edges;
    private final Map<Key<?>, Scope>   scopes;

    GraphImpl(Map<Key<?>, Node<?>> edges, Map<Key<?>, Scope> scopes) {
        this.edges = Map.copyOf(edges);
        this.scopes = Map.copyOf(scopes);
    }

    @Override
    public synchronized <T> Optional<Node<? extends T>> follow(Key<T> key) {
        //noinspection unchecked
        return Optional.ofNullable(edges.get(key))
                .map(n -> (Node<T>) n);
    }

    @Override
    public synchronized <T> Optional<Scope> scope(Key<T> key) {
        return Optional.ofNullable(scopes.get(key));
    }
}
