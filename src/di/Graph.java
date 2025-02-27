package di;

import java.util.Optional;
import java.util.function.Supplier;

public interface Graph {
    sealed interface Node<T> permits KeyNode, SupplierNode {}

    record KeyNode<T>(Key<T> key) implements Node<T> {}

    record SupplierNode<T>(Supplier<T> supplier) implements Node<T> {}

    <T> Optional<Node<? extends T>> follow(Key<T> key);

    <T> Optional<Scope> scope(Key<T> key);
}
