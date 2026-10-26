package engine;

import engine.lights.DirectionalLight;

import java.util.List;
import java.util.Objects;

/** Software support, independent of whether a native device is installed or available. */
public enum RenderCapabilities {
    CPU("cpu", 32),
    GPU("gpu", 2);

    public static final String COMMON_GAME_PROFILE = "game-v1";

    public enum Feature {
        PERSPECTIVE_CAMERA,
        POLYGON_GEOMETRY,
        DIFFUSE_MATERIALS,
        CUBE_TEXTURES,
        DIRECTIONAL_LIGHT,
        SKY,
        SKY_DISC,
        ATMOSPHERE,
        LOCAL_FOG,
        DEPTH_OF_FIELD,
        ADAPTIVE_RESOLUTION,
        TEMPORAL_RECONSTRUCTION,
        TEMPORAL_BUDGET,
        WORKER_CONTROL,
        TILE_CONTROL,
        OPTIONAL_ACCELERATION,
        ORTHOGRAPHIC_CAMERA,
        POINT_LIGHTS,
        ADVANCED_MATERIALS
    }

    public record Support(boolean supported, String reason) {}

    private static final Support SUPPORTED =
            new Support(true, "Supported; input combinations still require validation");

    /** Path names refer to immutable public render inputs, or a named scene instance. */
    public record Issue(String path, String reason) {}

    public static final class UnsupportedInput extends IllegalArgumentException {
        private final Issue issue;

        UnsupportedInput(String path, String reason) {
            super(path + ": " + reason);
            issue = new Issue(path, reason);
        }

        public Issue issue() {
            return issue;
        }
    }

    private final String id;
    private final int maximumDepth;

    RenderCapabilities(String id, int maximumDepth) {
        this.id = id;
        this.maximumDepth = maximumDepth;
    }

    public String id() {
        return id;
    }

    public int maximumDepth() {
        return maximumDepth;
    }

    public int minimumDimension() {
        return 64;
    }

    public int maximumDimension() {
        return 1600;
    }

    public int maximumBatchSamples() {
        return 8;
    }

    public static RenderCapabilities forBackend(String backend) {
        return switch (backend) {
            case "cpu" -> CPU;
            case "gpu" -> GPU;
            default -> throw new IllegalArgumentException("Backend must be cpu or gpu");
        };
    }

    public Support support(Feature feature) {
        Objects.requireNonNull(feature);
        if (this == CPU) {
            return SUPPORTED;
        }
        String reason =
                switch (feature) {
                    case PERSPECTIVE_CAMERA,
                                    POLYGON_GEOMETRY,
                                    DIFFUSE_MATERIALS,
                                    CUBE_TEXTURES,
                                    DIRECTIONAL_LIGHT,
                                    SKY,
                                    SKY_DISC,
                                    ATMOSPHERE,
                                    LOCAL_FOG,
                                    DEPTH_OF_FIELD ->
                            null;
                    case ADAPTIVE_RESOLUTION -> "GPU requires fixed resolution";
                    case TEMPORAL_RECONSTRUCTION, TEMPORAL_BUDGET ->
                            "GPU does not implement temporal reconstruction or budgets";
                    case WORKER_CONTROL, TILE_CONTROL ->
                            "GPU scheduling is device-managed; CPU workers and tiles do not apply";
                    case OPTIONAL_ACCELERATION -> "GPU requires acceleration";
                    case ORTHOGRAPHIC_CAMERA -> "GPU supports perspective cameras only";
                    case POINT_LIGHTS -> "GPU supports at most one directional light";
                    case ADVANCED_MATERIALS -> "GPU supports opaque diffuse polygon instances only";
                };
        return reason == null ? SUPPORTED : new Support(false, reason);
    }

    public void require(Feature feature, boolean requested, String path) {
        if (requested && !support(feature).supported()) {
            throw new UnsupportedInput(path, support(feature).reason());
        }
    }

    /** Returns the first unsupported input; successful validation allocates no issue collection. */
    public List<Issue> issues(WorldSnapshot world, RenderView view, RenderSettings settings) {
        try {
            validate(world, view, settings);
            return List.of();
        } catch (UnsupportedInput failure) {
            return List.of(failure.issue());
        }
    }

    public void validate(WorldSnapshot world, RenderView view, RenderSettings settings) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(view).camera().validated();
        validateSettings(settings);
        require(
                Feature.ORTHOGRAPHIC_CAMERA,
                view.camera().projection() != Camera.Projection.PERSPECTIVE,
                "view.camera.projection");
        // WorldSnapshot validates shared lighting/material combinations before either backend sees
        // it.
        if (this == GPU) {
            validateGpuWorld(world);
        }
    }

    public void validateSettings(RenderSettings settings) {
        Objects.requireNonNull(settings);
        if (settings.pathDepth() > maximumDepth) {
            throw new UnsupportedInput(
                    "settings.pathDepth", id + " supports depth 0.." + maximumDepth);
        }
        require(Feature.ADAPTIVE_RESOLUTION, settings.interactive(), "settings.interactive");
        require(Feature.TEMPORAL_RECONSTRUCTION, settings.temporal(), "settings.temporal");
        require(Feature.TEMPORAL_BUDGET, settings.temporalBudget(), "settings.temporalBudget");
        require(Feature.OPTIONAL_ACCELERATION, !settings.acceleration(), "settings.acceleration");
    }

    private static void validateGpuWorld(WorldSnapshot world) {
        if (world.instances().isEmpty() || !world.legacyObjects().isEmpty()) {
            throw new UnsupportedInput(
                    "world.instances",
                    "GPU requires nonempty polygon instances and no legacy objects");
        }
        if (world.lights().size() > 1
                || world.lights().stream()
                        .anyMatch(light -> !(light instanceof DirectionalLight))) {
            throw new UnsupportedInput(
                    "world.lights",
                    "GPU supports at most one directional light; point/area lights are"
                            + " unsupported");
        }
        for (var object : world.instances()) {
            var material = object.material();
            if (!(object.geometry() instanceof PolygonMesh)
                    || material.kind() != Material.Kind.DIFFUSE
                    || material.roughness() != 0
                    || material.emissive()
                    || material.scattering() != 0) {
                throw new UnsupportedInput(
                        "world.instances[" + object.name() + "]",
                        "GPU requires opaque diffuse polygon geometry without roughness, emission"
                                + " or scattering");
            }
        }
    }

    /** Input profile shared by both renderers; this does not probe hardware. */
    public static List<Issue> commonGameIssues(
            WorldSnapshot world, RenderView view, RenderSettings settings) {
        return GPU.issues(world, view, settings);
    }
}
