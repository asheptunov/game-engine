package di;

import di.annotations.Inject;
import di.annotations.Named;
import di.annotations.Qualifier;
import logging.LogManager;
import logging.Logger;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static di.Scope.PROTOTYPE;

public class Injector {
    private static final Logger LOG           = LogManager.instance().getThis();
    private static final Scope  DEFAULT_SCOPE = PROTOTYPE;

    private final Graph                    graph;
    private final Map<Key<?>, Supplier<?>> supplierCache = new HashMap<>();
    private final Map<Key<?>, Object>      singletons    = new HashMap<>();

    private Injector(Graph graph) {this.graph = graph;}

    public static Injector create(Module... modules) {
        var graphBuilder = new GraphBuilderImpl();
        for (Module module : modules) {
            module.configure(graphBuilder);
        }
        var graph = graphBuilder.build();
        return new Injector(graph);
    }

    public <T> T get(Class<T> klass) {
        return get((Type) klass);
    }

    public <T> T get(Type type) {
        return get(new Key.TypeKey<>(type));
    }

    public <T> T get(Key<T> key) {
        LOG.debug("Provisioning %s", key);
        return getSupplier(key).get();
    }

    private synchronized <T> Supplier<T> getSupplier(Key<T> key) {
        if (!supplierCache.containsKey(key)) {
            LOG.debug("Provisioning supplier for %s", key);
            var supplier = initSupplier(key);
            supplierCache.put(key, supplier);
        } else {
            LOG.debug("Recalling supplier for key %s", key);
        }
        //noinspection unchecked
        return (Supplier<T>) supplierCache.get(key);
    }

    private <T> Supplier<T> initSupplier(Key<T> key) {
        var node = graph.follow(key)
                .orElseGet(() -> new Graph.SupplierNode<>(initProtoSupplier(key)));
        var supplier = switch (node) {
            case Graph.KeyNode<? extends T> kn -> initDelegatingSupplier(key, kn.key());
            case Graph.SupplierNode<? extends T> sn -> new PrototypeSupplier<>(key, sn.supplier());
        };
        var scope = graph.scope(key)
                .orElse(DEFAULT_SCOPE);
        return switch (scope) {
            case PROTOTYPE -> supplier;
            case SINGLETON -> new SingletonSupplier<>(singletons, key, supplier);
        };
    }

    private <T, U extends T> Supplier<T> initDelegatingSupplier(Key<T> parentKey, Key<U> childKey) {
        return new DelegatingSupplier<>(parentKey, childKey, getSupplier(childKey));
    }

    private <T> Supplier<T> initProtoSupplier(Key<T> key) {
        var rawType = keyToRawType(key);
        return new PrototypeSupplier<>(key, () -> {
            var ctor = getCtor(rawType);
            var args = Arrays.stream(ctor.getAnnotatedParameterTypes())
                    .map(this::typeToKey)
                    .map(this::get)
                    .toArray();
            try {
                return ctor.newInstance(args);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            }
        });

    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> keyToRawType(Key<T> key) {
        var type = switch (key) {
            case Key.QualifiedKey<T> qk -> keyToRawType(qk.delegate());
            case Key.TypeKey<T> tk -> tk.type();
        };
        return (Class<T>) typeToRawType(type);
    }

    private Class<?> typeToRawType(Type type) {
        return switch (type) {
            case Class<?> c -> {
                if (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers())) {
                    throw new UnsupportedOperationException("Cannot instantiate non-static inner classes");
                }
                yield c;
            }
            case ParameterizedType pt -> typeToRawType(pt.getRawType());
            default -> throw new UnsupportedOperationException("" + type);
        };
    }

    private <T> Key<T> typeToKey(AnnotatedType type) {
        var qualifiers = Arrays.stream(type.getAnnotations())
                .filter(a -> a.annotationType().isAnnotationPresent(Qualifier.class))
                .toList();
        if (qualifiers.isEmpty()) {
            return new Key.TypeKey<>(type.getType());
        }
        if (qualifiers.size() > 1) {
            throw new UnsupportedOperationException(
                    "Only one qualifier is allowed; got " + qualifiers.size() + " on " + type);
        }
        var qualAnnotation = qualifiers.getFirst();
        var qualifier = switch (qualAnnotation) {
            case Named n -> new di.Qualifier.Name(n.value());
            default -> new di.Qualifier.Annotation(qualAnnotation);
        };
        return new Key.QualifiedKey<>(qualifier, new Key.TypeKey<>(type.getType()));
    }

    @SuppressWarnings("unchecked")
    private <T> Constructor<T> getCtor(Class<T> rawType) {
        var ctors = rawType.getDeclaredConstructors();
        var injectAnnotatedCtors = Arrays.stream(ctors)
                .filter(c -> c.isAnnotationPresent(Inject.class))
                .toList();
        if (injectAnnotatedCtors.size() > 1) {
            throw new IllegalArgumentException(
                    "Class must have exactly one constructor annotated with " + Inject.class + "; "
                            + rawType + " has " + injectAnnotatedCtors.size());
        }
        if (injectAnnotatedCtors.size() == 1) {
            return (Constructor<T>) injectAnnotatedCtors.getFirst();
        }
        // no inject-annotated ctor; look for default ctor
        return Arrays.stream(ctors)
                .filter(c -> c.getParameterCount() == 0)
                .findFirst()
                .map(c -> (Constructor<T>) c)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Class must have either one constructor annotated with " + Inject.class
                                + ", or a default constructor"));
    }

    private record DelegatingSupplier<T, U extends T>(Key<T> key, Key<U> delegateKey, Supplier<U> delegateSupplier)
            implements Supplier<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        @Override
        public T get() {
            LOG.debug("Delegating to supplier for %s", delegateKey);
            return delegateSupplier.get();
        }
    }

    private record PrototypeSupplier<T>(Key<T> key, Supplier<? extends T> delegate) implements Supplier<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        @Override
        public T get() {
            LOG.debug("Instantiating %s", key);
            return delegate.get();
        }
    }

    private record SingletonSupplier<T>(Map<Key<?>, Object> singletons, Key<T> key, Supplier<T> delegate)
            implements Supplier<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        @Override
        public T get() {
            if (!singletons.containsKey(key)) {
                LOG.debug("Instantiating singleton %s", key);
                singletons.put(key, delegate.get());
            } else {
                LOG.debug("Recalling singleton %s", key);
            }
            //noinspection unchecked
            return (T) singletons.get(key);
        }
    }
}
