package engine;

import engine.lights.Light;
import engine.lights.PointLight;
import engine.objects.RenderPrimitive;

import java.util.List;

/** Immutable world input. Revisions distinguish edit/undo cycles with equal contents. */
public record WorldSnapshot(
        long revision,
        List<SceneInstance> instances,
        List<RenderPrimitive> legacyObjects,
        List<Light> lights) {
    public WorldSnapshot {
        if (revision < 0) throw new IllegalArgumentException("World revision must be nonnegative");
        instances = List.copyOf(instances);
        legacyObjects = List.copyOf(legacyObjects);
        lights = List.copyOf(lights);
        if (instances.stream().anyMatch(java.util.Objects::isNull)
                || legacyObjects.stream().anyMatch(java.util.Objects::isNull)
                || lights.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("World contents cannot contain null");
        if (lights.stream().anyMatch(light -> !(light instanceof PointLight)))
            throw new IllegalArgumentException(
                    "Render sessions currently support immutable PointLight values only");
    }

    public static WorldSnapshot of(List<SceneInstance> instances, List<Light> lights) {
        return new WorldSnapshot(0, instances, List.of(), lights);
    }
}
