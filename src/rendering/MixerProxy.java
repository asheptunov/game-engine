package rendering;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class MixerProxy {
    public static <T> Builder<T> builder(Class<T> klass) {
        return new Builder<>(klass);
    }

    public static class Builder<T> {
        private final Class<T>         klass;
        private       Method           method;
        private final List<Channel<T>> channels = new ArrayList<>();

        private Builder(Class<T> klass) {
            this.klass = klass;
        }

        public Builder<T> withTarget(Supplier<Boolean> switchStateSupplier, T target) {
            channels.add(new Channel<>(switchStateSupplier, target));
            return this;
        }

        @SuppressWarnings("unchecked")
        public T build() {
            return (T) Proxy.newProxyInstance(
                    klass.getClassLoader(),
                    new Class[]{klass},
                    new Mixer<>(List.copyOf(channels)));
        }
    }

    private record Channel<T>(Supplier<Boolean> stateSupplier, T target) {}

    private static final class Mixer<T> implements InvocationHandler {
        private static final Set<Method> NOT_PROXIED_METHODS;

        static {
            try {
                NOT_PROXIED_METHODS = Set.of(
                        Object.class.getMethod("toString"),
                        Object.class.getMethod("equals", Object.class),
                        Object.class.getMethod("hashCode")
                );
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        private final List<Channel<T>> channels;

        private Mixer(List<Channel<T>> channels) {
            this.channels = channels;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (NOT_PROXIED_METHODS.contains(method)) {
                return method.invoke(this, args);
            }
            for (Channel<T> s : channels) {
                if (!s.stateSupplier().get()) {
                    continue;
                }
                try {
                    method.invoke(s.target(), args);
                    return null;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            return null;
        }
    }
}
