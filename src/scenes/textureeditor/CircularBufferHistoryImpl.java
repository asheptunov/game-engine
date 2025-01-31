package scenes.textureeditor;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;

public class CircularBufferHistoryImpl<T> implements History<T> {
    private final T[] buf;
    private       int cur    = 0;
    private       int oldest = 0;
    private       int newest = 0;

    public CircularBufferHistoryImpl(int maxSize, T initial) {
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

    @Override
    public Collection<T> getPast() {
        var res = new LinkedList<T>();
        int i = oldest;
        while (i <= cur) {
            // newest -> oldest iteration order
            res.addFirst(buf[i]);
            ++i;
            i %= buf.length;
        }
        return res;
    }

    @Override
    public Collection<T> getFuture() {
        var res = new LinkedList<T>();
        int i = cur + 1;
        while (i <= newest) {
            // newest -> oldest iteration order
            res.addFirst(buf[i]);
            ++i;
            i %= buf.length;
        }
        return res;
    }

    @Override
    public int size() {
        int newest = this.newest >= oldest
                ? this.newest
                : this.newest + buf.length;
        return newest - oldest + 1;
    }

    @Override
    public int maxSize() {
        return buf.length;
    }
}
