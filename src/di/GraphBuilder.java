package di;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public interface GraphBuilder {
    <T> QualifiedLinkBuilder<T> link(Key<T> key);

    default <T> LinkBuilder<T> link(Class<T> klass) {
        return link((Type) klass);
    }

    default <T> LinkBuilder<T> link(GenericType<T> genericType) {
        return link(genericType.getType());
    }

    <T> LinkBuilder<T> link(Type type);

    void install(Module module);

    Graph build();

    interface LinkBuilder<T> extends QualifiedLinkBuilder<T> {
        QualifiedLinkBuilder<T> qualified(Qualifier qualifier);

        default QualifiedLinkBuilder<T> named(String name) {
            return qualified(new Qualifier.Name(name));
        }

        default QualifiedLinkBuilder<T> annotated(Annotation annotation) {
            return qualified(new Qualifier.Annotation(annotation));
        }

        LinkBuilder<T> unqualified();
    }

    interface QualifiedLinkBuilder<T> extends ScopingBuilder<T> {
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
