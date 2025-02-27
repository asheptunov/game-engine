package di;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class GraphBuilderImpl implements GraphBuilder {
    private final Map<Key<?>, Graph.Node<?>> edges  = new HashMap<>();
    private final Map<Key<?>, Scope>         scopes = new HashMap<>();

    GraphBuilderImpl() {}

    @Override
    public <T> LinkBuilder<T> link(Key<T> key) {
        return new LinkBuilderImpl<>(key);
    }

    @Override
    public void install(Module module) {
        module.configure(this);
    }

    @Override
    public Graph build() {
        return new GraphImpl(edges, scopes);
    }

    public class LinkBuilderImpl<T> implements LinkBuilder<T>, ScopingBuilder<T> {
        private final Key<T> from;

        private LinkBuilderImpl(Key<T> from) {this.from = from;}

        @Override
        public ScopingBuilder<T> to(Key<T> key) {
            return addEdge(new Graph.KeyNode<>(key));
        }

        @Override
        public ScopingBuilder<T> to(Supplier<T> supplier) {
            return addEdge(new Graph.SupplierNode<>(supplier));
        }

        private ScopingBuilder<T> addEdge(Graph.Node<T> to) {
            synchronized (this) {
                if (edges.containsKey(from)) {
                    throw new IllegalArgumentException("key " + from + " was already linked");
                }
                edges.put(from, to);
                return this;
            }
        }

        @Override
        public void prototype() {
            addScope(Scope.PROTOTYPE);
        }

        @Override
        public void singleton() {
            addScope(Scope.SINGLETON);
        }

        private void addScope(Scope scope) {
            synchronized (this) {
                if (scopes.containsKey(from)) {
                    throw new IllegalArgumentException("key " + from + " was already scoped");
                }
                scopes.put(from, scope);
            }
        }
    }
}
