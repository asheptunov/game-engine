package misc.spliterators;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ReversedSpliterator<T> implements Spliterator<T> {
    private final Iterator<T> reversed;

    public static <T> ReversedSpliterator<T> reverse(Spliterator<T> spliterator) {
        return reverse(StreamSupport.stream(spliterator, false));
    }

    public static <T> ReversedSpliterator<T> reverse(Stream<T> stream) {
        return reverse(stream.iterator());
    }

    public static <T> ReversedSpliterator<T> reverse(Iterable<T> iterable) {
        return reverse(iterable.iterator());
    }

    public static <T> ReversedSpliterator<T> reverse(Iterator<T> iterator) {
        return new ReversedSpliterator<>(iterator);
    }

    private ReversedSpliterator(Iterator<T> delegate) {
        var reversed = new LinkedList<T>();
        while (delegate.hasNext()) {
            reversed.addFirst(delegate.next());
        }
        this.reversed = reversed.iterator();
    }

    public Stream<T> stream() {
        return StreamSupport.stream(this, false);
    }

    public Iterable<T> iterable() {
        return this::iterator;
    }

    public Iterator<T> iterator() {
        return reversed;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (reversed.hasNext()) {
            action.accept(reversed.next());
            return true;
        }
        return false;
    }

    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return 0;
    }

    @Override
    public int characteristics() {
        return 0;
    }
}
