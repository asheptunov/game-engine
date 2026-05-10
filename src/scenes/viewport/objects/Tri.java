package scenes.viewport.objects;

import math.Intersection;
import math.MollerTrumbore;
import math.Ray;
import math.Vec3;

import java.util.Optional;

public record Tri(Vec3 a, Vec3 b, Vec3 c) implements SceneObject {
    public Vec3 normal() {
        return b.sub(a).cross(c.sub(a)).normalized();
    }

    @Override
    public Optional<Intersection> intersect(Ray ray) {
        var edge1 = b.sub(a);
        var edge2 = c.sub(a);
        return MollerTrumbore.compute(ray, a, edge1, edge2)
                .filter(r -> r.u() >= 0 && r.v() >= 0 && r.u() + r.v() <= 1)
                .map(r -> new Intersection(ray.at(r.t()), normal(), r.t(), ray));
    }
}
