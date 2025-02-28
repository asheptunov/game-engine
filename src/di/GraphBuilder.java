package di;

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

        default ScopingBuilder<T> toProvider(GenericType<? extends Provider<? extends T>> providerGenericType) {
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
