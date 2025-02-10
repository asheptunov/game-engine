package misc.lambdas;

@FunctionalInterface
public interface TConsumer<T, E extends Exception> {
    void _accept(T t) throws E;

    default void accept(T t) {
        try {
            _accept(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default TConsumer<T, ?> andThen(TConsumer<? super T, ?> after) {
        return t -> {
            _accept(t);
            after._accept(t);
        };
    }
}
