package engine;

import engine.objects.SceneObject;
import math.Vec3;
import java.util.List;

/** Indexed triangle list with stable original primitive/face ordering. */
public sealed interface MeshGeometry extends List<SceneObject> permits IndexedMesh,TriangleMesh {
    List<Vec3> vertices();
    int[] indices();
    long sourceFaceId(int primitiveIndex);
    boolean closedBoundary();
}
