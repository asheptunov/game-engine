package di;

import di.annotations.Named;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public sealed interface Key<T> permits Key.TypeKey, Key.QualifiedKey {
    record TypeKey<T>(Type type) implements Key<T> {}

    record QualifiedKey<T>(Qualifier qualifier, Key<T> delegate) implements Key<T> {}

    static <T> Key<T> get(Class<T> klass) {
        return get((Type) klass);
    }

    static <T> Key<T> get(GenericType<T> genericType) {
        return get(genericType.getType());
    }

    static <T> Key<T> get(Type type) {
        return new TypeKey<>(type);
    }

    default Key<T> qualified(Qualifier qualifier) {
        if (this instanceof Key.QualifiedKey<T> qk) {
            throw new IllegalArgumentException("Cannot qualify " + this + " with '" + qualifier
                    + "' because it's already qualified with " + qk.qualifier());
        }
        return new QualifiedKey<>(qualifier, this);
    }

    default Key<T> named(String name) {
        return qualified(new Qualifier.Name(name));
    }

    default Key<T> annotated(Annotation annotation) {
        if (annotation instanceof Named n) {
            return named(n.value());
        }
        return qualified(new Qualifier.Annotation(annotation));
    }
}
