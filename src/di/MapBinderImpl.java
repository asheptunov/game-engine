package di;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MapBinderImpl<K, V> implements GraphBuilder.MapBinder<K, V>, GraphBuilder.QualifyingMapBinder<K, V> {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<Qualifier>        qualifier;
    private final Type                       keyType;
    private final Type                       valueType;
    private final Map<K, Key<? extends V>>   map = new HashMap<>();
    private final List<MapBinderImpl<?, ?>>  registry;

    private MapBinderImpl(List<MapBinderImpl<?, ?>> registry, Type keyType, Type valueType) {
        this.registry = registry;
        this.qualifier = Optional.empty();
        this.keyType = keyType;
        this.valueType = valueType;
    }

    private MapBinderImpl(List<MapBinderImpl<?, ?>> registry, Qualifier qualifier, Type keyType, Type valueType) {
        this.registry = registry;
        this.qualifier = Optional.of(qualifier);
        this.keyType = keyType;
        this.valueType = valueType;
    }

    @Override
    public KeyedBuilderImpl key(K key) {
        return new KeyedBuilderImpl(key);
    }

    @Override
    public MapBinderImpl<K, V> qualified(Qualifier qualifier) {
        if (this.qualifier.isPresent()) {
            throw new IllegalArgumentException("Cannot qualify a map binder twice");
        }
        var newBinder = new MapBinderImpl<K, V>(registry, qualifier, keyType, valueType);
        registry.add(newBinder);
        return newBinder;
    }

    Key<?> mapKey() {
        Key<?> base = Key.get(Types.mapOf(keyType, valueType));
        return qualifier.<Key<?>>map(base::qualified).orElse(base);
    }

    Graph.MapNode<K, V> buildNode() {
        return new Graph.MapNode<>(Map.copyOf(map));
    }

    public static class MapBinderBuilderImpl implements GraphBuilder.MapBinderBuilder {
        private final List<MapBinderImpl<?, ?>> registry;

        MapBinderBuilderImpl(List<MapBinderImpl<?, ?>> registry) {
            this.registry = registry;
        }

        @Override
        public <K> KeyedMapBinderBuilderImpl<K> from(Type fromType) {
            return new KeyedMapBinderBuilderImpl<>(registry, fromType);
        }

        public static class KeyedMapBinderBuilderImpl<K> implements GraphBuilder.KeyedMapBinderBuilder<K> {
            private final List<MapBinderImpl<?, ?>> registry;
            private final Type keyType;

            private KeyedMapBinderBuilderImpl(List<MapBinderImpl<?, ?>> registry, Type keyType) {
                this.registry = registry;
                this.keyType = keyType;
            }

            @Override
            public <V> MapBinderImpl<K, V> to(Type valueType) {
                var binder = new MapBinderImpl<K, V>(registry, keyType, valueType);
                registry.add(binder);
                return binder;
            }
        }
    }

    public class KeyedBuilderImpl implements KeyedBuilder<K, V> {
        private final K key;

        private KeyedBuilderImpl(K key) {this.key = key;}

        @Override
        public void value(Key<V> value) {
            addEntry(key, value);
        }

        @Override
        public QualifyingBuilderImpl value(Type valueType) {
            addEntry(key, Key.get(valueType));
            return new QualifyingBuilderImpl();
        }

        public class QualifyingBuilderImpl implements QualifyingBuilder {
            @Override
            public void qualified(Qualifier qualifier) {
                map.put(key, map.get(key).qualified(qualifier));
            }
        }
    }

    private void addEntry(K key, Key<? extends V> valueKey) {
        if (!Types.typeToRawType(this.keyType).isInstance(key)) {
            throw new IllegalArgumentException("Key is not a " + this.keyType);
        }
        if (!Types.typeToRawType(valueType).isAssignableFrom(Types.keyToRawType(valueKey))) {
            throw new IllegalArgumentException("Value type " + Types.keyToType(valueKey)
                    + " is not compatible with map value type " + this.valueType);
        }
        map.put(key, valueKey);
    }
}
