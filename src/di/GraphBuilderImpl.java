package di;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class GraphBuilderImpl implements GraphBuilder {
    private final Map<Key<?>, Graph.Node<?>> edges  = new HashMap<>();
    private final Map<Key<?>, Scope>         scopes = new HashMap<>();

    GraphBuilderImpl() {}

    @Override
    public <T> BindingBuilderImpl<T> bind(Key<T> key) {
        return new BindingBuilderImpl<>(key);
    }

    @Override
    public <T> BindingBuilderImpl<T> bind(Type type) {
        return new BindingBuilderImpl<>(new Key.TypeKey<>(type));
    }

    @Override
    public void install(Module module) {
        module.configure(this);
    }

    @Override
    public Graph build() {
        return new GraphImpl(edges, scopes);
    }

    public class BindingBuilderImpl<T> implements BindingBuilder<T> {
        private final Key<T> from;

        private BindingBuilderImpl(Key<T> from) {this.from = from;}

        @Override
        public BindingBuilderImpl<T> qualified(Qualifier qualifier) {
            if (from instanceof Key.QualifiedKey<T>) {
                throw new IllegalArgumentException("Key being bound is already qualified: " + from);
            }
            return new BindingBuilderImpl<>(new Key.QualifiedKey<>(qualifier, from));
        }

        @Override
        public BindingBuilderImpl<T> unqualified() {
            return this;
        }

        @Override
        public BindingBuilderImpl<T> to(Key<T> key) {
            return addEdgeTo(new Graph.KeyNode<>(key));
        }

        @Override
        public BindingBuilderImpl<T> toProvider(Key<? extends Provider<? extends T>> providerKey) {
            return addEdgeTo(new Graph.ProviderKeyNode<>(providerKey));
        }

        @Override
        public BindingBuilderImpl<T> toProvider(Provider<T> providerInstance) {
            return addEdgeTo(new Graph.ProviderNode<>(providerInstance));
        }

        BindingBuilderImpl<T> addEdgeTo(Graph.Node<T> to) {
            synchronized (this) {
                if (edges.containsKey(from)) {
                    throw new IllegalArgumentException("Key " + from + " was already bound");
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
