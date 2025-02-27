package di;

import java.lang.reflect.Type;
import java.util.function.Supplier;

public interface GraphBuilder {
    <T> LinkBuilder<T> link(Key<T> key);

    void install(Module module);

    Graph build();

    default <T> LinkBuilder<T> link(Class<T> klass) {
        return link((Type) klass);
    }

    default <T> LinkBuilder<T> link(Type type) {
        return link(new Key.TypeKey<>(type));
    }

    interface LinkBuilder<T> extends ScopingBuilder<T> {
        ScopingBuilder<T> to(Key<T> key);

        default ScopingBuilder<T> to(Class<? extends T> klass) {
            return to((Type) klass);
        }

        default ScopingBuilder<T> to(Type type) {
            return to(new Key.TypeKey<>(type));
        }

        ScopingBuilder<T> to(Supplier<T> supplier);

        default void to(T instance) {
            to(() -> instance).singleton();
        }
    }

    interface ScopingBuilder<T> {
        void prototype();

        void singleton();
    }
}
