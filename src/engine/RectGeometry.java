package engine;

import engine.objects.Rect;
import engine.objects.SceneObject;
import math.Vec3;
import java.util.List;

/** Open parallelogram geometry. */
public record RectGeometry(Vec3 origin, Vec3 edge1, Vec3 edge2) implements GeometryData {
    public RectGeometry {
        float area=edge1==null||edge2==null?Float.NaN:edge1.cross(edge2).lengthSq();
        if (!finite(origin) || !finite(edge1) || !finite(edge2) || !Float.isFinite(area) || area == 0)
            throw new IllegalArgumentException("Rect geometry must be finite and nondegenerate");
    }
    private static boolean finite(Vec3 v) {
        return v != null && Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z());
    }
    @Override public List<SceneObject> primitives() { return List.of(new Rect(origin, edge1, edge2)); }
}
