package misc.lambdas;

@FunctionalInterface
public interface TPredicate<T, E extends Exception> {
    boolean _test(T t) throws E;

    default boolean test(T t) {
        try {
            return _test(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default TPredicate<T, ?> and(TPredicate<? super T, ?> other) {
        return t -> _test(t) && other._test(t);
    }
}
