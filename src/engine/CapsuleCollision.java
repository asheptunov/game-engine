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
    private final PrimitiveBvh hierarchy;

    public CapsuleCollision(List<SceneInstance> instances) {
        this(instances, true);
    }

    /** The linear path is retained for collision equivalence measurements. */
    public CapsuleCollision(List<SceneInstance> instances, boolean accelerated) {
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
        var bounds = new PrimitiveBvh.Bounds[faces.size()];
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = new PrimitiveBvh.Bounds();
            bounds[i].include(faces.get(i).low);
            bounds[i].include(faces.get(i).high);
        }
        hierarchy = accelerated && bounds.length > 16 ? new PrimitiveBvh(bounds) : null;
    }

    /** Feet are the capsule bottom; height includes both hemispheres. Returns deepest contact. */
    public Contact contact(Vec3 feet, float radius, float height) {
        var query = new ContactQuery(feet, radius, height);
        if (hierarchy == null) {
            for (int i = 0; i < faces.size(); i++) {
                query.consider(i);
            }
        } else {
            for (int i = 0; i < hierarchy.nodes.length; ) {
                var node = hierarchy.nodes[i];
                var b = node.bounds;
                if (!query.overlaps(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                    i = node.escape;
                    continue;
                }
                if (node.leaf()) {
                    query.leaf(node);
                }
                i++;
            }
        }
        return query.contact;
    }

    /** Query-local winner preserves original face order for equal penetration depths. */
    private final class ContactQuery {
        private final Vec3 feet;
        private final Vec3 bottom;
        private final Vec3 top;
        private final float radius;
        private final float height;
        private Contact contact;
        private int bestIndex = Integer.MAX_VALUE;

        ContactQuery(Vec3 feet, float radius, float height) {
            this.feet = feet;
            this.radius = radius;
            this.height = height;
            bottom = feet.add(new Vec3(0, radius, 0));
            top = feet.add(new Vec3(0, height - radius, 0));
        }

        boolean overlaps(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return feet.x() + radius >= minX
                    && feet.x() - radius <= maxX
                    && feet.z() + radius >= minZ
                    && feet.z() - radius <= maxZ
                    && feet.y() + height >= minY
                    && feet.y() <= maxY;
        }

        void leaf(PrimitiveBvh.Node node) {
            for (int i = node.from; i < node.to; i++) {
                consider(hierarchy.order[i]);
            }
        }

        void consider(int index) {
            var face = faces.get(index);
            if (!overlaps(
                    face.low.x(),
                    face.low.y(),
                    face.low.z(),
                    face.high.x(),
                    face.high.y(),
                    face.high.z())) {
                return;
            }
            var delta = separation(bottom, top, face);
            float distance = delta.length();
            float depth = radius - distance;
            if (depth <= 0 || !wins(depth, index)) {
                return;
            }
            var normal = distance > 1e-6f ? delta.scale(1 / distance) : face.normal;
            contact = new Contact(normal, depth);
            bestIndex = index;
        }

        boolean wins(float depth, int index) {
            return contact == null
                    || depth > contact.depth()
                    || (depth == contact.depth() && index < bestIndex);
        }
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
