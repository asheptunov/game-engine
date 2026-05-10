package scenes.viewport.objects;

import math.Intersection;
import math.Ray;

import java.util.Optional;

public sealed interface SceneObject permits Tri, Rect {
    Optional<Intersection> intersect(Ray ray);
}
