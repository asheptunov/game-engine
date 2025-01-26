package misc.monads;

import java.util.function.Consumer;
import java.util.function.Function;

public class Either<L, R> {
    private       boolean isLeft;
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

    public Either<L, R> ifLeft(Consumer<? super L> consumer) {
        if (isLeft) {
            consumer.accept(left);
        }
        return this;
    }

    public Either<L, R> ifRight(Consumer<? super R> consumer) {
        if (!isLeft) {
            consumer.accept(right);
        }
        return this;
    }

    public <LL> Either<LL, R> mapLeft(Function<? super L, ? extends LL> mapper) {
        if (isLeft) {
            return Either.left(mapper.apply(left));
        }
        //noinspection unchecked
        return (Either<LL, R>) this;
    }

    public <RR> Either<L, RR> mapRight(Function<? super R, ? extends RR> mapper) {
        if (!isLeft) {
            return Either.right(mapper.apply(right));
        }
        //noinspection unchecked
        return (Either<L, RR>) this;
    }

    public <LL> Either<LL, R> flatMapLeft(Function<? super L, ? extends Either<? extends LL, ? extends R>> mapper) {
        if (isLeft) {
            //noinspection unchecked
            return (Either<LL, R>) mapper.apply(left);
        }
        //noinspection unchecked
        return (Either<LL, R>) this;
    }

    public <RR> Either<L, RR> flatMapRight(Function<? super R, ? extends Either<? extends L, ? extends RR>> mapper) {
        if (!isLeft) {
            //noinspection unchecked
            return (Either<L, RR>) mapper.apply(right);
        }
        //noinspection unchecked
        return (Either<L, RR>) this;
    }

    public <T> T fold(Function<? super L, ? extends T> leftMapper, Function<? super R, ? extends T> rightMapper) {
        return isLeft
                ? leftMapper.apply(left)
                : rightMapper.apply(right);
    }
}
