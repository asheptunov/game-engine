package engine;

import math.Intersection;
import math.MollerTrumbore;
import math.Ray;
import math.Vec3;
import engine.objects.Rect;
import engine.objects.SceneObject;
import engine.objects.Tri;
import engine.objects.Sphere;

/** Geometry prepared once per trace; misses allocate no intersection/Optional objects. */
final class TraceSurface {
    private final Vec3 origin, edge1, edge2, normal;
    private final boolean triangle;
    private Sphere sphere;

    TraceSurface(SceneObject object) {
        // Exhaustive over the sealed primitive types. A future curved primitive must reconsider self-shadowing.
        switch (object) {
            case Sphere s -> {
                sphere = s; origin = s.center(); edge1 = edge2 = Vec3.ZERO; triangle = false;
            }
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
        var o = ray.origin(); var d = ray.direction();
        return distance(o.x(), o.y(), o.z(), d.x(), d.y(), d.z());
    }

    float distance(float ox, float oy, float oz, float dx, float dy, float dz) {
        if (sphere != null) return sphere.distance(ox, oy, oz, dx, dy, dz);
        return MollerTrumbore.distance(ox, oy, oz, dx, dy, dz, origin, edge1, edge2, triangle);
    }

    Vec3 normal() { return normal; }
    Vec3 normalAt(float x, float y, float z) {
        return sphere == null ? normal : new Vec3(x, y, z).sub(sphere.center()).normalized();
    }
    boolean flat() { return sphere == null; }

    Intersection hit(Ray ray, float distance) {
        var p = ray.at(distance);
        return new Intersection(p, sphere == null ? normal : p.sub(sphere.center()).normalized(), distance, ray);
    }
}
