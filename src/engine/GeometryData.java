package engine;

import engine.objects.SceneObject;
import java.util.List;

/** Immutable render geometry stored by a shared geometry asset. */
public sealed interface GeometryData permits SphereGeometry, RectGeometry, BoxGeometry, TriangleMesh, EditableMeshGeometry {
    List<SceneObject> primitives();
    default boolean closedBoundary() { return false; }
}
