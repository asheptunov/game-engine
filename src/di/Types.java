package di;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

final class Types {
    private Types() {}

    static Class<?> typeToRawType(Type type) {
        return switch (type) {
            case Class<?> c -> {
                if (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers())) {
                    throw new UnsupportedOperationException("Cannot instantiate non-static inner classes");
                }
                yield c;
            }
            case ParameterizedType pt -> typeToRawType(pt.getRawType());
            default -> throw new UnsupportedOperationException("" + type);
        };
    }

    static <T> Type keyToType(Key<T> key) {
        return switch (key) {
            case Key.QualifiedKey<T> qk -> keyToType(qk.delegate());
            case Key.TypeKey<T> tk -> tk.type();
        };
    }

    static <T> Class<T> keyToRawType(Key<T> key) {
        //noinspection unchecked
        return (Class<T>) typeToRawType(keyToType(key));
    }
}
