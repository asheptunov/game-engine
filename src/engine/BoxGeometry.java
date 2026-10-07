package engine;

import engine.objects.SceneObject;
import java.util.List;

/** Canonical local box spanning -1..1; retains the transport's existing box identity. */
public final class BoxGeometry implements GeometryData {
    public static final BoxGeometry UNIT = new BoxGeometry();
    private BoxGeometry() {}
    @Override public List<SceneObject> primitives() { return SceneInstance.box(); }
    @Override public boolean closedBoundary() { return true; }
    @Override public boolean equals(Object other) { return other instanceof BoxGeometry; }
    @Override public int hashCode() { return BoxGeometry.class.hashCode(); }
    @Override public String toString() { return "BoxGeometry.UNIT"; }
}
