package engine;

import java.util.Objects;

/** References independently shared geometry and material assets. */
public record GeometryComponent(GeometryId geometryId, MaterialId materialId) {
    public GeometryComponent {
        Objects.requireNonNull(geometryId, "geometryId");
        Objects.requireNonNull(materialId, "materialId");
    }
}
