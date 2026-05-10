package di;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Graph {
    sealed interface Node<T> permits KeyNode, ProviderKeyNode, ProviderMethodNode, ProviderNode, ListNode, MapNode {}

    record KeyNode<T>(Key<T> key) implements Node<T> {}

    record ProviderKeyNode<T>(Key<? extends Provider<? extends T>> providerKey) implements Node<T> {}

    record ProviderMethodNode<T>(Module moduleInstance, Method providerMethod) implements Node<T> {}

    record ProviderNode<T>(Provider<T> provider) implements Node<T> {}

    record ListNode<E>(List<Key<? extends E>> elementKeys) implements Node<List<E>> {}

    record MapNode<K, V>(Map<K, Key<? extends V>> entryKeys) implements Node<Map<K, V>> {}

    <T> Optional<Node<? extends T>> follow(Key<T> key);

    <T> Optional<Scope> scope(Key<T> key);
}
