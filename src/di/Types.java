package di;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class Types {
    private Types() {}

    static Type listOf(Type elementType) {
        return parameterizedType(List.class, elementType);
    }

    static Type mapOf(Type keyType, Type valueType) {
        return parameterizedType(Map.class, keyType, valueType);
    }

    static Type parameterizedType(Class<?> rawType, Type... typeArgs) {
        // Mirrors JDK ParameterizedTypeImpl equals/hashCode so keys round-trip correctly
        // against types produced by reflection (e.g. from GenericType<List<String>>(){}).
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return typeArgs.clone();
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof ParameterizedType other)) return false;
                return Objects.equals(getRawType(), other.getRawType())
                        && Objects.equals(getOwnerType(), other.getOwnerType())
                        && Arrays.equals(getActualTypeArguments(), other.getActualTypeArguments());
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(getActualTypeArguments())
                        ^ Objects.hashCode(getRawType())
                        ^ Objects.hashCode(getOwnerType());
            }

            @Override
            public String toString() {
                return rawType.getName()
                        + "<"
                        + Arrays.stream(typeArgs)
                                .map(Type::getTypeName)
                                .collect(Collectors.joining(", "))
                        + ">";
            }
        };
    }

    static Class<?> typeToRawType(Type type) {
        return switch (type) {
            case Class<?> c -> {
                if (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers())) {
                    throw new UnsupportedOperationException(
                            "Cannot instantiate non-static inner classes");
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
