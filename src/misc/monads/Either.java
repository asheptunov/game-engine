package misc.monads;

import misc.lambdas.TConsumer;
import misc.lambdas.TFunction;

public class Either<L, R> {
    private final boolean isLeft;
    private final L       left;
    private final R       right;

    private Either(boolean isLeft, L left, R right) {
        this.isLeft = isLeft;
        this.left = left;
        this.right = right;
    }

    public static <L, R> Either<L, R> left(L left) {
        return new Either<>(true, left, null);
    }

    public static <L, R> Either<L, R> right(R right) {
        return new Either<>(false, null, right);
    }

    public boolean isLeft() {
        return isLeft;
    }

    public boolean isRight() {
        return !isLeft;
    }

    public L getLeft() {
        if (!isLeft) {
            throw new IllegalStateException();
        }
        return left;
    }

    public R getRight() {
        if (isLeft) {
            throw new IllegalStateException();
        }
        return right;
    }

    public Either<L, R> ifLeft(TConsumer<? super L, ?> consumer) {
        if (isLeft) {
            consumer.accept(left);
        }
        return this;
    }

    public Either<L, R> ifRight(TConsumer<? super R, ?> consumer) {
        if (!isLeft) {
            consumer.accept(right);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <LL> Either<LL, R> mapLeft(TFunction<? super L, ? extends LL, ?> map) {
        if (isLeft) {
            return Either.left(map.apply(left));
        }
        return (Either<LL, R>) this;
    }

    @SuppressWarnings("unchecked")
    public <RR> Either<L, RR> mapRight(TFunction<? super R, ? extends RR, ?> map) {
        if (!isLeft) {
            return Either.right(map.apply(right));
        }
        return (Either<L, RR>) this;
    }

    @SuppressWarnings("unchecked")
    public <LL> Either<LL, R> flatMapLeft(TFunction<? super L, ? extends Either<? extends LL, ? extends R>, ?> map) {
        if (isLeft) {
            return (Either<LL, R>) map.apply(left);
        }
        return (Either<LL, R>) this;
    }

    @SuppressWarnings("unchecked")
    public <RR> Either<L, RR> flatMapRight(TFunction<? super R, ? extends Either<? extends L, ? extends RR>, ?> map) {
        if (!isLeft) {
            return (Either<L, RR>) map.apply(right);
        }
        return (Either<L, RR>) this;
    }

    public <T> T fold(TFunction<? super L, ? extends T, ?> leftMap,
                      TFunction<? super R, ? extends T, ?> rightMap) {
        return isLeft
                ? leftMap.apply(left)
                : rightMap.apply(right);
    }
}
