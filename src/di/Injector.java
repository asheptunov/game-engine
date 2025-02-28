package di;

import di.annotations.Inject;
import di.annotations.Named;
import di.annotations.Provides;
import di.annotations.Qualifier;
import di.annotations.Singleton;
import logging.LogManager;
import logging.Logger;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static di.Scope.PROTOTYPE;
import static di.Scope.SINGLETON;

public class Injector {
    private static final Logger LOG           = LogManager.instance().getThis();
    private static final Scope  DEFAULT_SCOPE = PROTOTYPE;

    private final Graph                    graph;
    private final Map<Key<?>, Provider<?>> providerCache = new HashMap<>();
    private final Map<Key<?>, Object>      singletons    = new HashMap<>();

    private Injector(Graph graph) {this.graph = graph;}

    public static Injector create(Module... modules) {
        var graphBuilder = new GraphBuilderImpl();
        for (Module module : modules) {
            module.configure(graphBuilder);
            configureUsingProviderMethods(module, graphBuilder);
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

    public <T> T get(GenericType<T> genericType) {
        return get(genericType.getType());
    }

    public <T> T get(Key<T> key) {
        LOG.debug("Provisioning %s", key);
        return getProvider(key).get();
    }

    private static void configureUsingProviderMethods(Module module, GraphBuilderImpl graphBuilder) {
        Arrays.stream(module.getClass().getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Provides.class))
                .forEach(method -> {
                    var bindingBuilder = graphBuilder
                            .bind(inferKey(method))
                            .addEdgeTo(new Graph.ProviderMethodNode<>(module, method));
                    inferScope(method).ifPresent(bindingBuilder::addScope);
                });
    }

    private static Key<?> inferKey(Method providerMethod) {
        return typeToKey(
                getAnnotatedType(
                        providerMethod.getGenericReturnType(),
                        providerMethod.getAnnotations()));
    }

    private static AnnotatedType getAnnotatedType(Type type, Annotation[] annotations) {
        return new AnnotatedType() {
            @Override
            public Type getType() {
                return type;
            }

            @Override
            public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
                //noinspection unchecked
                return (T) Arrays.stream(getAnnotations())
                        .filter(annotationClass::isInstance)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public Annotation[] getAnnotations() {
                return annotations;
            }

            @Override
            public Annotation[] getDeclaredAnnotations() {
                return annotations;
            }
        };
    }

    private static Optional<Scope> inferScope(Method providerMethod) {
        return providerMethod.isAnnotationPresent(Singleton.class) ? Optional.of(SINGLETON) : Optional.empty();
    }

    private synchronized <T> Provider<T> getProvider(Key<T> key) {
        if (!providerCache.containsKey(key)) {
            LOG.debug("Provisioning provider for %s", key);
            var provider = initProvider(key);
            providerCache.put(key, provider);
        } else {
            LOG.debug("Recalling provider for key %s", key);
        }
        //noinspection unchecked
        return (Provider<T>) providerCache.get(key);
    }

    private <T> Provider<T> initProvider(Key<T> key) {
        var node = graph.follow(key)
                .orElseGet(() -> new Graph.ProviderNode<>(initProtoProvider(key)));
        Provider<T> provider = switch (node) {
            case Graph.KeyNode<? extends T> kn -> //noinspection rawtypes,unchecked
                    new DelegatingProvider(key, kn.key(), getProvider(kn.key()));
            case Graph.ProviderKeyNode<? extends T> pkn ->
                    new PrototypeProvider<>(key, () -> getProvider(pkn.providerKey()).get().get());
            case Graph.ProviderMethodNode<? extends T> pmn ->
                    new MethodProvider<>(key, pmn.moduleInstance(), pmn.providerMethod());
            case Graph.ProviderNode<? extends T> sn -> new PrototypeProvider<>(key, sn.provider());
        };
        var scope = graph.scope(key)
                .orElse(DEFAULT_SCOPE);
        return switch (scope) {
            case PROTOTYPE -> provider;
            case SINGLETON -> new SingletonProvider<>(singletons, key, provider);
        };
    }

    private <T> Provider<T> initProtoProvider(Key<T> key) {
        var rawType = keyToRawType(key);
        return new PrototypeProvider<>(key, () -> {
            var ctor = getCtor(rawType);
            var args = Arrays.stream(ctor.getAnnotatedParameterTypes())
                    .map(Injector::typeToKey)
                    .map(this::get)
                    .toArray();
            try {
                ctor.setAccessible(true);
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

    private static <T> Key<T> typeToKey(AnnotatedType type) {
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
                        "To be injectable, " + rawType + " must have either one constructor annotated with "
                                + Inject.class + ", or a default constructor"));
    }

    private record DelegatingProvider<T, U extends T>(Key<T> key, Key<U> delegateKey, Provider<U> delegateProvider)
            implements Provider<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        @Override
        public T get() {
            LOG.debug("Delegating to provider for %s", delegateKey);
            return delegateProvider.get();
        }
    }

    private class MethodProvider<T> implements Provider<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        private final Key<T> key;
        private final Module moduleInstance;
        private final Method providerMethod;

        private MethodProvider(Key<T> key, Module moduleInstance, Method providerMethod) {
            this.key = key;
            this.moduleInstance = moduleInstance;
            this.providerMethod = providerMethod;
        }

        @Override
        public T get() {
            LOG.debug("Invoking provider method %s", providerMethod);
            var args = new Object[providerMethod.getParameterCount()];
            for (int i = 0; i < providerMethod.getParameterCount(); ++i) {
                var paramType = getAnnotatedType(
                        providerMethod.getGenericParameterTypes()[i],
                        providerMethod.getParameterAnnotations()[i]);
                var paramKey = typeToKey(paramType);
                var arg = Injector.this.get(paramKey);
                args[i] = arg;
            }
            try {
                providerMethod.setAccessible(true);
                //noinspection unchecked
                return (T) providerMethod.invoke(moduleInstance, args);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            }
        }
    }

    private record PrototypeProvider<T>(Key<T> key, Provider<? extends T> delegate) implements Provider<T> {
        private static final Logger LOG = LogManager.instance().getThis();

        @Override
        public T get() {
            LOG.debug("Instantiating %s", key);
            return delegate.get();
        }
    }

    private record SingletonProvider<T>(Map<Key<?>, Object> singletons, Key<T> key, Provider<T> delegate)
            implements Provider<T> {
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
