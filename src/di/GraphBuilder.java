package di;

import di.annotations.Named;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public interface GraphBuilder {
    <T> QualifiedBindingBuilder<T> bind(Key<T> key);

    default <T> BindingBuilder<T> bind(Class<T> klass) {
        return bind((Type) klass);
    }

    default <T> BindingBuilder<T> bind(GenericType<T> genericType) {
        return bind(genericType.getType());
    }

    <T> BindingBuilder<T> bind(Type type);

    ListBinderBuilder bindList();

    MapBinderBuilder bindMap();

    interface ListBinderBuilder {
        default <T> QualifyingListBinder<T> of(Class<T> elementClass) {
            return of((Type) elementClass);
        }

        default <T> QualifyingListBinder<T> of(GenericType<T> elementGenericType) {
            return of(elementGenericType.getType());
        }

        <T> QualifyingListBinder<T> of(Type elementType);
    }

    interface QualifyingListBinder<T> extends ListBinder<T> {
        ListBinder<T> qualified(Qualifier qualifier);

        default ListBinder<T> named(String name) {
            return qualified(new Qualifier.Name(name));
        }

        default ListBinder<T> annotated(Annotation annotation) {
            if (annotation instanceof Named n) {
                return named(n.value());
            }
            return qualified(new Qualifier.Annotation(annotation));
        }
    }

    interface ListBinder<T> {
        void add(Key<? extends T> key);

        default QualifyingBuilder<T> add(Class<? extends T> klass) {
            return add((Type) klass);
        }

        default QualifyingBuilder<T> add(GenericType<? extends T> genericType) {
            return add(genericType.getType());
        }

        QualifyingBuilder<T> add(Type type);

        interface QualifyingBuilder<T> {
            void qualified(Qualifier qualifier);

            default void named(String name) {
                qualified(new Qualifier.Name(name));
            }

            default void annotatedWith(Annotation annotation) {
                if (annotation instanceof Named n) {
                    named(n.value());
                    return;
                }
                qualified(new Qualifier.Annotation(annotation));
            }
        }
    }

    interface MapBinderBuilder {
        default <K> KeyedMapBinderBuilder<K> from(Class<K> fromClass) {
            return from((Type) fromClass);
        }

        default <K> KeyedMapBinderBuilder<K> from(GenericType<K> fromGenericType) {
            return from(fromGenericType.getType());
        }

        <K> KeyedMapBinderBuilder<K> from(Type fromType);
    }

    interface KeyedMapBinderBuilder<K> {
        default <V> QualifyingMapBinder<K, V> to(Class<V> toClass) {
            return to((Type) toClass);
        }

        default <V> QualifyingMapBinder<K, V> to(GenericType<V> toGenericType) {
            return to(toGenericType.getType());
        }

        <V> QualifyingMapBinder<K, V> to(Type toType);
    }

    interface QualifyingMapBinder<K, V> extends MapBinder<K, V> {
        MapBinder<K, V> qualified(Qualifier qualifier);

        default MapBinder<K, V> named(String name) {
            return qualified(new Qualifier.Name(name));
        }

        default MapBinder<K, V> annotated(Annotation annotation) {
            if (annotation instanceof Named named) {
                return named(named.value());
            }
            return qualified(new Qualifier.Annotation(annotation));
        }
    }

    interface MapBinder<K, V> {
        KeyedBuilder<K, V> key(K key);

        interface KeyedBuilder<K, V> {
            void value(Key<V> value);

            default QualifyingBuilder value(Class<K> valueClass) {
                return value((Type) valueClass);
            }

            default QualifyingBuilder value(GenericType<K> valueGenericType) {
                return value(valueGenericType.getType());
            }

            QualifyingBuilder value(Type valueType);
        }

        interface QualifyingBuilder {
            void qualified(Qualifier qualifier);

            default void named(String name) {
                qualified(new Qualifier.Name(name));
            }

            default void annotated(Annotation annotation) {
                if (annotation instanceof Named n) {
                    named(n.value());
                    return;
                }
                qualified(new Qualifier.Annotation(annotation));
            }
        }
    }

    void install(Module module);

    Graph build();

    interface BindingBuilder<T> extends QualifiedBindingBuilder<T> {
        QualifiedBindingBuilder<T> qualified(Qualifier qualifier);

        default QualifiedBindingBuilder<T> named(String name) {
            return qualified(new Qualifier.Name(name));
        }

        default QualifiedBindingBuilder<T> annotated(Annotation annotation) {
            return qualified(new Qualifier.Annotation(annotation));
        }

        BindingBuilder<T> unqualified();
    }

    interface QualifiedBindingBuilder<T> extends ScopingBuilder<T> {
        ScopingBuilder<T> to(Key<T> key);

        default ScopingBuilder<T> to(Class<? extends T> klass) {
            return to((Type) klass);
        }

        default ScopingBuilder<T> to(GenericType<? extends T> genericType) {
            return to(genericType.getType());
        }

        default ScopingBuilder<T> to(Type type) {
            return to(new Key.TypeKey<>(type));
        }

        ScopingBuilder<T> toProvider(Key<? extends Provider<? extends T>> providerKey);

        default ScopingBuilder<T> toProvider(Class<? extends Provider<? extends T>> providerClass) {
            return toProvider(new Key.TypeKey<>(providerClass));
        }

        default ScopingBuilder<T> toProvider(
                GenericType<? extends Provider<? extends T>> providerGenericType) {
            return toProvider(new Key.TypeKey<>(providerGenericType.getType()));
        }

        ScopingBuilder<T> toProvider(Provider<T> providerInstance);

        default void toInstance(T instance) {
            toProvider(() -> instance).singleton();
        }
    }

    interface ScopingBuilder<T> {
        void prototype();

        void singleton();
    }
}
