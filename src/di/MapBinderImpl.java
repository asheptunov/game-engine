package di;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MapBinderImpl<K, V> implements GraphBuilder.MapBinder<K, V>, GraphBuilder.QualifyingMapBinder<K, V> {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<Qualifier>      qualifier;
    private final Type                     keyType;
    private final Type                     valueType;
    private final Map<K, Key<? extends V>> map = new HashMap<>();

    private MapBinderImpl(Qualifier qualifier, Type keyType, Type valueType) {
        this.qualifier = Optional.of(qualifier);
        this.keyType = keyType;
        this.valueType = valueType;
    }

    private MapBinderImpl(Type keyType, Type valueType) {
        this.qualifier = Optional.empty();
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
        return new MapBinderImpl<>(qualifier, keyType, valueType);
    }

    public static class MapBinderBuilderImpl implements GraphBuilder.MapBinderBuilder {
        MapBinderBuilderImpl() {}

        @Override
        public <K> KeyedMapBinderBuilderImpl<K> from(Type fromType) {
            return new KeyedMapBinderBuilderImpl<>(fromType);
        }

        public static class KeyedMapBinderBuilderImpl<K> implements GraphBuilder.KeyedMapBinderBuilder<K> {
            private final Type keyType;

            private KeyedMapBinderBuilderImpl(Type keyType) {this.keyType = keyType;}

            @Override
            public <V> MapBinderImpl<K, V> to(Type valueType) {
                return new MapBinderImpl<>(keyType, valueType);
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
