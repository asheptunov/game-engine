package engine;

import engine.objects.SceneObject;
import engine.objects.Sphere;
import math.Vec3;
import java.util.List;

/** Analytic sphere geometry. */
public record SphereGeometry(Vec3 center, float radius) implements GeometryData {
    public SphereGeometry { new Sphere(center, radius); }
    @Override public List<SceneObject> primitives() { return List.of(new Sphere(center, radius)); }
    @Override public boolean closedBoundary() { return true; }
}
