package scenes.viewport;

import math.Intersection;
import math.MollerTrumbore;
import math.Ray;
import math.Vec3;
import scenes.viewport.objects.Rect;
import scenes.viewport.objects.SceneObject;
import scenes.viewport.objects.Tri;

/** Geometry prepared once per trace; misses allocate no intersection/Optional objects. */
final class TraceSurface {
    private final SceneObject object;
    private final Vec3 origin, edge1, edge2, normal;
    private final boolean triangle;

    TraceSurface(SceneObject object) {
        this.object = object;
        if (object instanceof Tri t) {
            origin = t.a(); edge1 = t.b().sub(t.a()); edge2 = t.c().sub(t.a());
            triangle = true;
        } else if (object instanceof Rect r) {
            origin = r.origin(); edge1 = r.edge1(); edge2 = r.edge2();
            triangle = false;
        } else {
            origin = edge1 = edge2 = normal = null;
            triangle = false;
            return;
        }
        // Degenerate surfaces never pass the determinant check.
        var cross = edge1.cross(edge2);
        normal = cross.lengthSq() == 0 ? Vec3.ZERO : cross.normalized();
    }

    float distance(Ray ray) {
        if (origin == null) return object.intersect(ray).map(Intersection::distance).orElse(Float.POSITIVE_INFINITY);
        return MollerTrumbore.distance(ray, origin, edge1, edge2, triangle);
    }

    Intersection hit(Ray ray, float distance) {
        if (origin == null) return object.intersect(ray).orElse(null);
        return new Intersection(ray.at(distance), normal, distance, ray);
    }
}
