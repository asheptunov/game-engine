package misc.spliterators;

import java.util.Collection;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ChunkedSpliterator<T, C extends Collection<T>> implements Spliterator<C> {
    private final Iterator<T> delegate;
    private final int         chunkSize;
    private final Supplier<C> chunkSupplier;

    public static <T, C extends Collection<T>> ChunkedSpliterator<T, C> chunk(
            Iterator<T> delegate, int chunkSize, Supplier<C> chunkSupplier) {
        return new ChunkedSpliterator<>(delegate, chunkSize, chunkSupplier);
    }

    private ChunkedSpliterator(Iterator<T> delegate, int chunkSize, Supplier<C> chunkSupplier) {
        if (delegate == null) {
            throw new IllegalArgumentException();
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException();
        }
        if (chunkSupplier == null) {
            throw new IllegalArgumentException();
        }
        this.delegate = delegate;
        this.chunkSize = chunkSize;
        this.chunkSupplier = chunkSupplier;
    }

    public Stream<C> stream() {
        return StreamSupport.stream(this, false);
    }

    @Override
    public boolean tryAdvance(Consumer<? super C> action) {
        C chunk = chunkSupplier.get();
        while (chunk.size() < chunkSize && delegate.hasNext()) {  // pack the chunk
            chunk.add(delegate.next());
        }
        // packed the chunk, or ran out of elements (or both)
        if (chunk.isEmpty()) {
            return false;
        }
        action.accept(chunk);
        return true;
    }

    @Override
    public Spliterator<C> trySplit() {
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
