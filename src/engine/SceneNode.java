package engine;

import java.util.Objects;

/** Immutable scene-graph node. Nullable components mean that component is absent. */
public record SceneNode(
        NodeId id,
        String label,
        NodeId parentId,
        Transform localTransform,
        GeometryComponent geometry,
        PointLightComponent light,
        CameraComponent camera) {
    public SceneNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localTransform, "localTransform");
        label = Labels.checked(label);
        if (id.equals(parentId)) throw new IllegalArgumentException("A node cannot parent itself");
    }

    public SceneNode withLabel(String value) {
        return new SceneNode(id, value, parentId, localTransform, geometry, light, camera);
    }

    public SceneNode withParent(NodeId value) {
        return new SceneNode(id, label, value, localTransform, geometry, light, camera);
    }

    public SceneNode withLocalTransform(Transform value) {
        return new SceneNode(id, label, parentId, value, geometry, light, camera);
    }

    public SceneNode withGeometry(GeometryComponent value) {
        return new SceneNode(id, label, parentId, localTransform, value, light, camera);
    }

    public SceneNode withLight(PointLightComponent value) {
        return new SceneNode(id, label, parentId, localTransform, geometry, value, camera);
    }

    public SceneNode withCamera(CameraComponent value) {
        return new SceneNode(id, label, parentId, localTransform, geometry, light, value);
    }
}
