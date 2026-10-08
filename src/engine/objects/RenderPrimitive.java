package engine.objects;

import math.Intersection;
import math.Ray;

import java.util.Optional;

public sealed interface RenderPrimitive permits Tri, Rect, Sphere {
    Optional<Intersection> intersect(Ray ray);
}
