package di;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ListBinderImpl<T> implements GraphBuilder.ListBinder<T>, GraphBuilder.QualifyingListBinder<T> {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<Qualifier>    qualifier;
    private final Type                   elementType;
    private final List<Key<? extends T>> list = new ArrayList<>();

    private ListBinderImpl(Qualifier qualifier, Type elementType) {
        this.qualifier = Optional.of(qualifier);
        this.elementType = elementType;
    }

    private ListBinderImpl(Type elementType) {
        this.qualifier = Optional.empty();
        this.elementType = elementType;
    }

    @Override
    public void add(Key<? extends T> key) {
        addElement(key);
    }

    @Override
    public QualifyingBuilderImpl add(Type type) {
        addElement(new Key.TypeKey<>(type));
        return new QualifyingBuilderImpl(list.size() - 1);  // gives opportunity to qualify
    }

    @Override
    public GraphBuilder.ListBinder<T> qualified(Qualifier qualifier) {
        if (this.qualifier.isPresent()) {
            throw new IllegalArgumentException("Cannot qualify a list binder twice");
        }
        return new ListBinderImpl<>(qualifier, elementType);
    }

    public static class ListBinderBuilderImpl implements GraphBuilder.ListBinderBuilder {
        ListBinderBuilderImpl() {}

        @Override
        public <T> ListBinderImpl<T> of(Type elementType) {
            return new ListBinderImpl<>(elementType);
        }
    }

    public class QualifyingBuilderImpl implements QualifyingBuilder<T> {
        private final int index;

        private QualifyingBuilderImpl(int index) {
            this.index = index;
        }

        @Override
        public void qualified(Qualifier qualifier) {
            list.set(index, list.get(index).qualified(qualifier));
        }
    }

    private void addElement(Key<? extends T> key) {
        if (!Types.typeToRawType(this.elementType)
                .isAssignableFrom(Types.keyToRawType(key))) {
            throw new IllegalArgumentException("Element type " + Types.keyToType(key)
                    + " is not compatible with list element type " + this.elementType);
        }
        list.add(key);
    }
}
