package engine;

import engine.lights.DirectionalLight;
import engine.lights.Light;
import engine.lights.PointLight;
import engine.objects.RenderPrimitive;

import java.util.List;

/** Immutable world input. Revisions distinguish edit/undo cycles with equal contents. */
public record WorldSnapshot(
        long revision,
        List<SceneInstance> instances,
        List<RenderPrimitive> legacyObjects,
        List<Light> lights,
        Sky sky) {
    public WorldSnapshot(
            long revision,
            List<SceneInstance> instances,
            List<RenderPrimitive> legacyObjects,
            List<Light> lights) {
        this(revision, instances, legacyObjects, lights, Sky.BLACK);
    }

    public WorldSnapshot {
        if (revision < 0) throw new IllegalArgumentException("World revision must be nonnegative");
        instances = List.copyOf(instances);
        legacyObjects = List.copyOf(legacyObjects);
        lights = List.copyOf(lights);
        if (instances.stream().anyMatch(java.util.Objects::isNull)
                || legacyObjects.stream().anyMatch(java.util.Objects::isNull)
                || lights.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("World contents cannot contain null");
        java.util.Objects.requireNonNull(sky, "Sky is required; use Sky.BLACK for no environment");
        if (lights.stream()
                .anyMatch(
                        light ->
                                !(light instanceof PointLight)
                                        && !(light instanceof DirectionalLight))) {
            throw new IllegalArgumentException(
                    "Render sessions support immutable point and directional lights only");
        }
        validateEnvironment(instances, lights, sky);
    }

    static void validateEnvironment(List<SceneInstance> instances, List<Light> lights, Sky sky) {
        if ((!sky.equals(Sky.BLACK) || lights.stream().anyMatch(DirectionalLight.class::isInstance))
                && instances.stream()
                        .anyMatch(
                                instance -> instance.material().kind() != Material.Kind.DIFFUSE)) {
            throw new IllegalArgumentException(
                    "Directional lights and sky currently require diffuse materials; mirror,"
                            + " dielectric and volume transport are unsupported");
        }
    }

    public static WorldSnapshot of(List<SceneInstance> instances, List<Light> lights) {
        return new WorldSnapshot(0, instances, List.of(), lights);
    }
}
