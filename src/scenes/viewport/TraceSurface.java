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
    private final Vec3 origin, edge1, edge2, normal;
    private final boolean triangle;

    TraceSurface(SceneObject object) {
        // Exhaustive over the sealed primitive types. A future curved primitive must reconsider self-shadowing.
        switch (object) {
            case Tri t -> {
                origin = t.a(); edge1 = t.b().sub(t.a()); edge2 = t.c().sub(t.a()); triangle = true;
            }
            case Rect r -> {
                origin = r.origin(); edge1 = r.edge1(); edge2 = r.edge2(); triangle = false;
            }
        }
        // Degenerate surfaces never pass the determinant check.
        var cross = edge1.cross(edge2);
        normal = cross.lengthSq() == 0 ? Vec3.ZERO : cross.normalized();
    }

    float distance(Ray ray) {
        return MollerTrumbore.distance(ray, origin, edge1, edge2, triangle);
    }

    float distance(float ox, float oy, float oz, float dx, float dy, float dz) {
        return MollerTrumbore.distance(ox, oy, oz, dx, dy, dz, origin, edge1, edge2, triangle);
    }

    Vec3 normal() { return normal; }

    Intersection hit(Ray ray, float distance) {
        return new Intersection(ray.at(distance), normal, distance, ray);
    }
}
