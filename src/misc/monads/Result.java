package misc.monads;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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

    public Result<S, F> ifSuccess(Consumer<S> consumer) {
        var newEither = delegate.ifLeft(consumer);
        return delegate == newEither ? this : new Result<>(newEither);
    }

    public Result<S, F> ifFailure(Consumer<F> consumer) {
        var newDelegate = delegate.ifRight(consumer);
        return delegate == newDelegate ? this : new Result<>(newDelegate);
    }

    public <SS> Result<SS, F> mapSuccess(Function<S, SS> mapper) {
        var newDelegate = delegate.mapLeft(mapper);
        //noinspection unchecked
        return delegate == newDelegate ? (Result<SS, F>) this : new Result<>(newDelegate);
    }

    public <FF> Result<S, FF> mapFailure(Function<F, FF> mapper) {
        var newDelegate = delegate.mapRight(mapper);
        //noinspection unchecked
        return delegate == newDelegate ? (Result<S, FF>) this : new Result<>(newDelegate);
    }

    public Result<S, F> filter(Predicate<S> predicate, Function<S, F> toFailureFunction) {
        if (isFailure()) {
            return this;
        }
        if (predicate.test(getSuccess())) {
            return this;
        }
        return new Result<>(Either.right(toFailureFunction.apply(getSuccess())));
    }

    public Result<S, F> recover(Predicate<F> predicate, Function<F, S> toSuccessFunction) {
        if (isSuccess()) {
            return this;
        }
        if (!predicate.test(getFailure())) {
            return this;
        }
        return new Result<>(Either.left(toSuccessFunction.apply(getFailure())));
    }
}
