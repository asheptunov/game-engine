package scenes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class SceneAwareProxyBuilder {
    private       Class<?>[]                          interfaces;
    private final Map<Class<? extends Scene>, Object> targetByScene = new HashMap<>();
    private       Supplier<Scene>                     sceneSupplier;

    private SceneAwareProxyBuilder() {}

    public static SceneAwareProxyBuilder create() {
        return new SceneAwareProxyBuilder();
    }

    public SceneAwareProxyBuilder withInterface(Class<?> anInterface) {
        return withInterfaces(anInterface);
    }

    public SceneAwareProxyBuilder withInterfaces(Class<?>... interfaces) {
        this.interfaces = interfaces;
        if (interfaces == null) {
            throw new IllegalArgumentException();
        }
        for (Class<?> anInterface : interfaces) {
            if (anInterface == null) {
                throw new IllegalArgumentException();
            }
            if (!anInterface.isInterface()) {
                throw new IllegalArgumentException(anInterface.getName());
            }
        }
        return this;
    }

    public SceneAwareProxyBuilder withTargetForScene(Class<? extends Scene> sceneClass, Object target) {
        if (sceneClass == null) {
            throw new IllegalArgumentException();
        }
        for (Class<?> anInterface : interfaces) {
            if (!anInterface.isInstance(target)) {
                throw new IllegalArgumentException(anInterface.getName());
            }
        }
        targetByScene.put(sceneClass, target);
        return this;
    }

    public SceneAwareProxyBuilder withSceneSupplier(Supplier<Scene> sceneSupplier) {
        this.sceneSupplier = sceneSupplier;
        return this;
    }

    public Object build() {
        if (interfaces == null) {
            throw new IllegalArgumentException();
        }
        if (targetByScene.isEmpty()) {
            throw new IllegalArgumentException();
        }
        if (sceneSupplier == null) {
            throw new IllegalArgumentException();
        }
        return Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                interfaces,
                new SceneAwareInvocationHandler(targetByScene, sceneSupplier));
    }

    private static class SceneAwareInvocationHandler implements InvocationHandler {
//        private static final Set<Method> SHORT_CIRCUIT_METHODS = Set.of(Object.class.getMethods());

        private final Map<Class<? extends Scene>, Object> targetsByScene;
        private final Supplier<Scene>                     sceneSupplier;

        private SceneAwareInvocationHandler(Map<Class<? extends Scene>, Object> targetsByScene,
                                            Supplier<Scene> sceneSupplier) {
            this.targetsByScene = targetsByScene;
            this.sceneSupplier = sceneSupplier;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // TODO don't quite remember why I had to do this... something related to hashCode?
//            if (SHORT_CIRCUIT_METHODS.contains(method)) {
//                // toString / equals / hashCode / etc.
//                return method.invoke(this, args);
//            }
            var scene = sceneSupplier.get();
            var target = Optional.of(scene.getClass())
                    .filter(targetsByScene::containsKey)
                    .map(targetsByScene::get)
                    .orElseThrow(IllegalArgumentException::new);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
