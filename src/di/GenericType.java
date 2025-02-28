package di;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class GenericType<T> {
    public Type getType() {
        Type currentGenericType = null;
        Class<?> currentRawType = this.getClass();
        while (!GenericType.class.equals(currentRawType)) {
            currentGenericType = currentRawType.getGenericSuperclass();
            currentRawType = currentRawType.getSuperclass();
        }
        return switch (currentGenericType) {
            case Class<?> _ -> Object.class;
            case ParameterizedType pt -> pt.getActualTypeArguments()[0];
            default -> throw new IllegalStateException("" + currentGenericType);
        };
    }
}
