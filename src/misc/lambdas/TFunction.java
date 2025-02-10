package misc.lambdas;

@FunctionalInterface
public interface TFunction<T, R, E extends Exception> {
    R _apply(T t) throws E;

    default R apply(T t) {
        try {
            return _apply(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default <V> TFunction<T, V, ?> andThen(TFunction<? super R, ? extends V, ?> after) {
        return t -> after._apply(_apply(t));
    }
}
