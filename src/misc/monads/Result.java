package misc.monads;

import misc.lambdas.TConsumer;
import misc.lambdas.TFunction;
import misc.lambdas.TPredicate;

public class Result<S, F> {
    private final Either<S, F> delegate;

    private Result(Either<S, F> delegate) {
        this.delegate = delegate;
    }

    public static <S, F> Result<S, F> success(S value) {
        return new Result<>(Either.left(value));
    }

    public static <S, F> Result<S, F> failure(F value) {
        return new Result<>(Either.right(value));
    }

    public boolean isSuccess() {
        return delegate.isLeft();
    }

    public boolean isFailure() {
        return delegate.isRight();
    }

    public S getSuccess() {
        return delegate.getLeft();
    }

    public F getFailure() {
        return delegate.getRight();
    }

    public Result<S, F> ifSuccess(TConsumer<S, ?> consumer) {
        var newEither = delegate.ifLeft(consumer);
        return delegate == newEither
                ? this
                : new Result<>(newEither);
    }

    public Result<S, F> ifFailure(TConsumer<F, ?> consumer) {
        var newDelegate = delegate.ifRight(consumer);
        return delegate == newDelegate
                ? this
                : new Result<>(newDelegate);
    }

    @SuppressWarnings("unchecked")
    public <SS> Result<SS, F> mapSuccess(TFunction<S, SS, ?> map) {
        var newDelegate = delegate.mapLeft(map);
        return delegate == newDelegate
                ? (Result<SS, F>) this
                : new Result<>(newDelegate);
    }

    @SuppressWarnings("unchecked")
    public <FF> Result<S, FF> mapFailure(TFunction<F, FF, ?> map) {
        var newDelegate = delegate.mapRight(map);
        return delegate == newDelegate
                ? (Result<S, FF>) this
                : new Result<>(newDelegate);
    }

    @SuppressWarnings("unchecked")
    public <SS> Result<SS, F> flatMapSuccess(TFunction<? super S, ? extends Result<? extends SS, ? extends F>, ?> map) {
        var newDelegate = delegate.<SS>flatMapLeft(map
                .andThen(r -> (Result<? extends SS, ? extends F>) r)
                .andThen(r -> r.delegate));
        return delegate == newDelegate
                ? (Result<SS, F>) this
                : new Result<>(newDelegate);
    }

    @SuppressWarnings("unchecked")
    public <FF> Result<S, FF> flatMapFailure(TFunction<? super F, Result<? extends S, ? extends FF>, ?> map) {
        var newDelegate = delegate.<FF>flatMapRight(map
                .andThen(r -> (Result<? extends S, ? extends FF>) r)
                .andThen(r -> r.delegate));
        return delegate == newDelegate
                ? (Result<S, FF>) this
                : new Result<>(newDelegate);
    }

    public Result<S, F> filter(TPredicate<S, ?> predicate, TFunction<S, F, ?> toFailureFunction) {
        if (isFailure()) {
            return this;
        }
        if (predicate.test(getSuccess())) {
            return this;
        }
        return new Result<>(Either.right(toFailureFunction.apply(getSuccess())));
    }

    public Result<S, F> recover(TPredicate<F, ?> predicate, TFunction<F, S, ?> toSuccessFunction) {
        if (isSuccess()) {
            return this;
        }
        if (!predicate.test(getFailure())) {
            return this;
        }
        return new Result<>(Either.left(toSuccessFunction.apply(getFailure())));
    }

    public <T> T fold(TFunction<? super S, ? extends T, ?> successMap,
                      TFunction<? super F, ? extends T, ?> failureMap) {
        return delegate.fold(successMap, failureMap);
    }
}
