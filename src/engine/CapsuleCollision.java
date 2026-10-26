package engine;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Immutable, two-sided world-space triangle collision surface, independent of rendering. */
public final class CapsuleCollision {
    public record Contact(Vec3 normal, float depth) {}

    private record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 normal, Vec3 low, Vec3 high) {
        Face(Vec3 a, Vec3 b, Vec3 c) {
            this(
                    a,
                    b,
                    c,
                    b.sub(a).cross(c.sub(a)).normalized(),
                    new Vec3(
                            Math.min(a.x(), Math.min(b.x(), c.x())),
                            Math.min(a.y(), Math.min(b.y(), c.y())),
                            Math.min(a.z(), Math.min(b.z(), c.z()))),
                    new Vec3(
                            Math.max(a.x(), Math.max(b.x(), c.x())),
                            Math.max(a.y(), Math.max(b.y(), c.y())),
                            Math.max(a.z(), Math.max(b.z(), c.z()))));
        }
    }

    private final List<Face> faces;

    public CapsuleCollision(List<SceneInstance> instances) {
        var result = new ArrayList<Face>();
        for (var instance : instances) {
            if (!(instance.geometry() instanceof PolygonMesh mesh)) {
                throw new IllegalArgumentException(
                        "Walking requires polygon meshes: " + instance.name());
            }
            var vertices = new java.util.HashMap<Long, Vec3>();
            for (var vertex : mesh.editableVertices()) {
                vertices.put(vertex.id(), instance.transform().point(vertex.position()));
            }
            for (var face : mesh.faces()) {
                var ids = face.vertexIds();
                for (int i = 1; i + 1 < ids.size(); i++) {
                    result.add(
                            new Face(
                                    vertices.get(ids.getFirst()),
                                    vertices.get(ids.get(i)),
                                    vertices.get(ids.get(i + 1))));
                }
            }
        }
        faces = List.copyOf(result);
    }

    /** Feet are the capsule bottom; height includes both hemispheres. Returns deepest contact. */
    public Contact contact(Vec3 feet, float radius, float height) {
        Contact contact = null;
        var bottom = feet.add(new Vec3(0, radius, 0));
        var top = feet.add(new Vec3(0, height - radius, 0));
        for (var face : faces) {
            if (feet.x() + radius < face.low.x()
                    || feet.x() - radius > face.high.x()
                    || feet.z() + radius < face.low.z()
                    || feet.z() - radius > face.high.z()
                    || feet.y() + height < face.low.y()
                    || feet.y() > face.high.y()) {
                continue;
            }
            var delta = separation(bottom, top, face);
            float distance = delta.length();
            float depth = radius - distance;
            if (depth <= 0 || (contact != null && depth <= contact.depth())) {
                continue;
            }
            var normal = distance > 1e-6f ? delta.scale(1 / distance) : face.normal;
            contact = new Contact(normal, depth);
        }
        return contact;
    }

    private static Vec3 separation(Vec3 bottom, Vec3 top, Face face) {
        if (crossesFace(bottom, top, face)) {
            return Vec3.ZERO;
        }
        var best = bottom.sub(closest(bottom, face));
        var other = top.sub(closest(top, face));
        if (other.lengthSq() < best.lengthSq()) {
            best = other;
        }
        best = shorter(best, segmentSeparation(bottom, top, face.a, face.b));
        best = shorter(best, segmentSeparation(bottom, top, face.b, face.c));
        best = shorter(best, segmentSeparation(bottom, top, face.c, face.a));
        return best;
    }

    private static Vec3 shorter(Vec3 a, Vec3 b) {
        return a.lengthSq() <= b.lengthSq() ? a : b;
    }

    private static boolean crossesFace(Vec3 bottom, Vec3 top, Face face) {
        var axis = top.sub(bottom);
        float denominator = face.normal.dot(axis);
        if (Math.abs(denominator) <= 1e-8f) {
            return false;
        }
        float t = face.normal.dot(face.a.sub(bottom)) / denominator;
        if (t < 0 || t > 1) {
            return false;
        }
        var point = bottom.add(axis.scale(t));
        return point.sub(closest(point, face)).lengthSq() < 1e-10f;
    }

    private static Vec3 segmentSeparation(Vec3 p, Vec3 q, Vec3 a, Vec3 b) {
        var u = q.sub(p);
        var v = b.sub(a);
        var w = p.sub(a);
        float uu = u.dot(u);
        float vv = v.dot(v);
        float uv = u.dot(v);
        float uw = u.dot(w);
        float vw = v.dot(w);
        float determinant = uu * vv - uv * uv;
        float s = determinant > 1e-10f ? Math.clamp((uv * vw - vv * uw) / determinant, 0, 1) : 0;
        float t = (uv * s + vw) / vv;
        if (t < 0) {
            t = 0;
            s = Math.clamp(-uw / uu, 0, 1);
        } else if (t > 1) {
            t = 1;
            s = Math.clamp((uv - uw) / uu, 0, 1);
        }
        return p.add(u.scale(s)).sub(a.add(v.scale(t)));
    }

    /** Closest point on a triangle using its vertex, edge and face Voronoi regions. */
    private static Vec3 closest(Vec3 p, Face f) {
        var ab = f.b.sub(f.a);
        var ac = f.c.sub(f.a);
        var ap = p.sub(f.a);
        float d1 = ab.dot(ap);
        float d2 = ac.dot(ap);
        if (d1 <= 0 && d2 <= 0) {
            return f.a;
        }
        var bp = p.sub(f.b);
        float d3 = ab.dot(bp);
        float d4 = ac.dot(bp);
        if (d3 >= 0 && d4 <= d3) {
            return f.b;
        }
        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            return f.a.add(ab.scale(d1 / (d1 - d3)));
        }
        var cp = p.sub(f.c);
        float d5 = ab.dot(cp);
        float d6 = ac.dot(cp);
        if (d6 >= 0 && d5 <= d6) {
            return f.c;
        }
        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            return f.a.add(ac.scale(d2 / (d2 - d6)));
        }
        float va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            return f.b.add(f.c.sub(f.b).scale((d4 - d3) / ((d4 - d3) + (d5 - d6))));
        }
        float inverse = 1 / (va + vb + vc);
        return f.a.add(ab.scale(vb * inverse)).add(ac.scale(vc * inverse));
    }
}
