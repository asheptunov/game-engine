package engine.objects;

import math.Intersection;
import math.Ray;
import math.Vec3;
import java.util.Optional;

public record Sphere(Vec3 center, float radius) implements RenderPrimitive {
    public Sphere {
        if (!Float.isFinite(radius) || radius <= 0 || !Float.isFinite(center.x())
                || !Float.isFinite(center.y()) || !Float.isFinite(center.z()))
            throw new IllegalArgumentException("Sphere needs finite center and positive radius");
    }
    /** Works for unnormalized local directions and rays starting inside. */
    public float distance(float ox,float oy,float oz,float dx,float dy,float dz) {
        float x=ox-center.x(), y=oy-center.y(), z=oz-center.z();
        float a=dx*dx+dy*dy+dz*dz, b=x*dx+y*dy+z*dz, c=x*x+y*y+z*z-radius*radius;
        float disc=b*b-a*c;
        if (a == 0 || disc < 0) return Float.POSITIVE_INFINITY;
        float root=(float)Math.sqrt(disc);
        // Stable quadratic roots avoid cancellation for rays far from the sphere.
        float q=-b-Math.copySign(root,b);
        float t0=q/a, t1=q == 0 ? -b/a : c/q;
        float near=Math.min(t0,t1), far=Math.max(t0,t1);
        return near > 1e-6f ? near : far > 1e-6f ? far : Float.POSITIVE_INFINITY;
    }
    @Override public Optional<Intersection> intersect(Ray ray) {
        var o=ray.origin(); var d=ray.direction();
        float t=distance(o.x(),o.y(),o.z(),d.x(),d.y(),d.z());
        if (!Float.isFinite(t)) return Optional.empty();
        var p=ray.at(t);
        return Optional.of(new Intersection(p,p.sub(center).normalized(),t,ray));
    }
}
