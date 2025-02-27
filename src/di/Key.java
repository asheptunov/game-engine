package di;

import java.lang.reflect.Type;

public sealed interface Key<T> permits Key.TypeKey, Key.QualifiedKey {
    record TypeKey<T>(Type type) implements Key<T> {}

    record QualifiedKey<T>(Qualifier qualifier, Key<T> delegate) implements Key<T> {}
}
