package scenes.textureeditor;

import java.util.Optional;

public class CircularBufferHistoryImpl<T> implements History<T> {
    private final T[] buf;
    private       int cur    = 0;
    private       int oldest = 0;
    private       int newest = 0;

    public CircularBufferHistoryImpl(T initial, int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException();
        }
        //noinspection unchecked
        buf = (T[]) new Object[maxSize];
        buf[0] = initial;
    }

    @Override
    public void record(T current) {
        ++cur;
        cur %= buf.length;
        newest = cur;
        buf[cur] = current;
        if (newest - oldest + 1 > buf.length) {
            ++oldest;
            oldest %= buf.length;
        }
    }

    @Override
    public Optional<T> goBack() {
        if (cur == oldest) {
            return Optional.empty();
        }
        --cur;
        cur %= buf.length;
        return Optional.of(buf[cur]);
    }

    @Override
    public Optional<T> goForward() {
        if (cur == newest) {
            return Optional.empty();
        }
        ++cur;
        cur %= buf.length;
        return Optional.of(buf[cur]);
    }
}
