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

    default Key<T> named(String name) {
        if (this instanceof Key.QualifiedKey<T> qk) {
            throw new IllegalArgumentException("Cannot qualify " + this + " with name '" + name
                    + "' because it's already qualified with " + qk.qualifier());
        }
        return new QualifiedKey<>(new Qualifier.Name(name), this);
    }

    default Key<T> annotated(Annotation annotation) {
        if (this instanceof Key.QualifiedKey<T> qk) {
            throw new IllegalArgumentException("Cannot qualify " + this + " with " + annotation
                    + " because it's already qualified with " + qk.qualifier());
        }
        if (annotation instanceof Named n) {
            return named(n.value());
        }
        return new QualifiedKey<>(new Qualifier.Annotation(annotation), this);
    }
}
