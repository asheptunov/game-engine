package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** An ideal 4x gameplay sight with a planar view relay, physical housing and reticle. */
final class ScopeMesh {
    static final float EYEPIECE_Z = -.10f;
    static final float OBJECTIVE_Z = .270762f;
    static final float EYE_RELIEF = .072537f;
    static final float AXIS_Y = .115f;
    private static final int SIDES = 128;
    private static final Material OPTIC =
            new Material("Scope view relay", Vec3.ZERO)
                    .withViewRelay(new engine.ViewRelay(4, OBJECTIVE_Z + .014f - EYEPIECE_Z));
    private static final Material BLACK =
            new Material("Scope interior", new Vec3(.015f, .018f, .02f));
    private static final List<SceneInstance> PARTS = build();

    private ScopeMesh() {}

    static List<SceneInstance> instances(String prefix, Transform pose) {
        return PARTS.stream()
                .map(
                        part ->
                                new SceneInstance(
                                        prefix + part.name(),
                                        part.geometry(),
                                        pose,
                                        part.material()))
                .toList();
    }

    private static List<SceneInstance> build() {
        var result = new ArrayList<SceneInstance>();
        result.add(new SceneInstance("view", entrance(), Transform.IDENTITY, OPTIC));
        result.add(new SceneInstance("baffle", baffle(), Transform.IDENTITY, BLACK));
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        // Four circular stations form an open bore, not an opaque capped cylinder.
        float[][] stations = {
            {EYEPIECE_Z - .012f, .032f}, {OBJECTIVE_Z + .012f, .048f},
            {OBJECTIVE_Z + .012f, .043f}, {EYEPIECE_Z - .012f, .027f}
        };
        for (var station : stations) {
            ring(vertices, station[1], station[0]);
        }
        for (int r = 0; r < 4; r++) {
            join(faces, r * SIDES, ((r + 1) % 4) * SIDES);
        }
        result.add(
                new SceneInstance(
                        "housing",
                        PolygonMesh.surface(vertices, faces, vertices.size(), faces.size()),
                        Transform.IDENTITY,
                        BLACK));
        // Opaque reticle wires sit just in front of the deterministic optical entrance.
        for (int axis = 0; axis < 2; axis++) {
            var half =
                    axis == 0
                            ? new Vec3(.025f, .00012f, .00012f)
                            : new Vec3(.00012f, .025f, .00012f);
            var unit = SceneInstance.box();
            var points =
                    unit.editableVertices().stream()
                            .map(
                                    v ->
                                            new PolygonMesh.Vertex(
                                                    v.id(),
                                                    new Vec3(
                                                            v.position().x() * half.x(),
                                                            AXIS_Y + v.position().y() * half.y(),
                                                            EYEPIECE_Z
                                                                    - .002f
                                                                    + v.position().z() * half.z())))
                            .toList();
            result.add(
                    new SceneInstance(
                            "reticle/" + axis,
                            PolygonMesh.surface(
                                    points, unit.faces(), points.size(), unit.faces().size()),
                            Transform.IDENTITY,
                            BLACK));
        }
        return List.copyOf(result);
    }

    private static PolygonMesh entrance() {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        vertices.add(new PolygonMesh.Vertex(0, new Vec3(0, AXIS_Y, EYEPIECE_Z)));
        ring(vertices, .024f, EYEPIECE_Z);
        cap(faces, 0, true);
        return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
    }

    private static PolygonMesh baffle() {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        ring(vertices, .024f, EYEPIECE_Z);
        ring(vertices, .032f, EYEPIECE_Z);
        join(faces, 0, SIDES);
        return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
    }

    private static void cap(List<PolygonMesh.Face> faces, int base, boolean reverse) {
        for (int i = 0; i < SIDES; i++) {
            face(
                    faces,
                    reverse,
                    (long) base,
                    (long) base + 1 + i,
                    (long) base + 1 + (i + 1) % SIDES);
        }
    }

    private static void ring(List<PolygonMesh.Vertex> vertices, float radius, float z) {
        for (int i = 0; i < SIDES; i++) {
            double angle = 2 * Math.PI * i / SIDES;
            vertices.add(
                    new PolygonMesh.Vertex(
                            vertices.size(),
                            new Vec3(
                                    radius * (float) Math.cos(angle),
                                    AXIS_Y + radius * (float) Math.sin(angle),
                                    z)));
        }
    }

    private static void join(List<PolygonMesh.Face> faces, int back, int front) {
        for (int i = 0; i < SIDES; i++) {
            int next = (i + 1) % SIDES;
            face(
                    faces,
                    false,
                    (long) back + i,
                    (long) back + next,
                    (long) front + next,
                    (long) front + i);
        }
    }

    private static void face(List<PolygonMesh.Face> faces, boolean reverse, Long... ids) {
        var values = new ArrayList<Long>(List.of(ids));
        if (reverse) {
            java.util.Collections.reverse(values);
        }
        faces.add(new PolygonMesh.Face(faces.size(), values));
    }
}
