package scenes.textureeditor;

import java.util.Collection;
import java.util.Optional;

public interface History<T> {
    void record(T current);

    Optional<T> goBack();

    Optional<T> goForward();

    Collection<T> getPast();

    Collection<T> getFuture();
}
