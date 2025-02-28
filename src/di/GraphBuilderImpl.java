package di;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class GraphBuilderImpl implements GraphBuilder {
    private final Map<Key<?>, Graph.Node<?>> edges  = new HashMap<>();
    private final Map<Key<?>, Scope>         scopes = new HashMap<>();

    GraphBuilderImpl() {}

    @Override
    public <T> LinkBuilderImpl<T> link(Key<T> key) {
        return new LinkBuilderImpl<>(key);
    }

    @Override
    public <T> LinkBuilderImpl<T> link(Type type) {
        return new LinkBuilderImpl<>(new Key.TypeKey<>(type));
    }

    @Override
    public void install(Module module) {
        module.configure(this);
    }

    @Override
    public Graph build() {
        return new GraphImpl(edges, scopes);
    }

    public class LinkBuilderImpl<T> implements LinkBuilder<T> {
        private final Key<T> from;

        private LinkBuilderImpl(Key<T> from) {this.from = from;}

        @Override
        public LinkBuilderImpl<T> qualified(Qualifier qualifier) {
            if (from instanceof Key.QualifiedKey<T>) {
                throw new IllegalArgumentException("Key being linked is already qualified: " + from);
            }
            return new LinkBuilderImpl<>(new Key.QualifiedKey<>(qualifier, from));
        }

        @Override
        public LinkBuilderImpl<T> unqualified() {
            return this;
        }

        @Override
        public LinkBuilderImpl<T> to(Key<T> key) {
            return addEdgeTo(new Graph.KeyNode<>(key));
        }

        @Override
        public LinkBuilderImpl<T> toProvider(Key<? extends Provider<? extends T>> providerKey) {
            return addEdgeTo(new Graph.ProviderKeyNode<>(providerKey));
        }

        @Override
        public LinkBuilderImpl<T> toProvider(Provider<T> providerInstance) {
            return addEdgeTo(new Graph.ProviderNode<>(providerInstance));
        }

        LinkBuilderImpl<T> addEdgeTo(Graph.Node<T> to) {
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

        void addScope(Scope scope) {
            synchronized (this) {
                if (scopes.containsKey(from)) {
                    throw new IllegalArgumentException("key " + from + " was already scoped");
                }
                scopes.put(from, scope);
            }
        }
    }
}
