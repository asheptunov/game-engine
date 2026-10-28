package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** An upright three-lens sight: eyepiece, image-erecting relay and objective. Units are metres. */
final class ScopeMesh {
    static final float EYEPIECE_Z = -.10f;
    static final float OBJECTIVE_Z = .270762f;
    static final float EYE_RELIEF = .072537f;
    static final float AXIS_Y = .115f;
    private static final int SIDES = 128;
    private static final int RINGS = 64;
    private static final Material GLASS =
            new Material(
                    "Scope glass", new Vec3(1, 1, 1), Material.Kind.DIELECTRIC, 1.5f, Vec3.ZERO);
    private static final Material BLACK =
            new Material("Scope interior", new Vec3(.015f, .018f, .02f));
    private static final List<SceneInstance> PARTS = build();

    private ScopeMesh() {}

    static List<SceneInstance> instances(String prefix, Transform pose) {
        return instances(prefix, pose, 25);
    }

    static List<SceneInstance> instances(String prefix, Transform pose, float focus) {
        return PARTS.stream()
                .map(
                        part ->
                                new SceneInstance(
                                        prefix + part.name(),
                                        part.geometry(),
                                        Transform.compose(pose, focused(part, focus)),
                                        part.material()))
                .toList();
    }

    private static Transform focused(SceneInstance part, float focus) {
        // Thin-lens finite-object correction: move the objective forward at nearer focus.
        float shift = part.name().equals("objective") ? .16f * .16f / focus : 0;
        return new Transform(new Vec3(0, 0, shift), Vec3.ZERO, new Vec3(1, 1, 1));
    }

    private static List<SceneInstance> build() {
        var result = new ArrayList<SceneInstance>();
        result.add(
                new SceneInstance(
                        "eyepiece",
                        lens(EYEPIECE_Z, .024f, curvature(.04f, .022f), .022f),
                        Transform.IDENTITY,
                        GLASS));
        result.add(
                new SceneInstance(
                        "objective",
                        lens(OBJECTIVE_Z, .042f, curvature(.16f, .014f), .014f),
                        Transform.IDENTITY,
                        GLASS));
        result.add(
                new SceneInstance(
                        "relay",
                        lens(EYEPIECE_Z + .125666f, .024f, curvature(.04f, .022f), .022f),
                        Transform.IDENTITY,
                        GLASS));
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
        // Physical reticle wires at the intermediate image plane, between eyepiece and relay.
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
                                                                    + .04f
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

    private static float curvature(float focalLength, float thickness) {
        return (focalLength
                        + (float)
                                Math.sqrt(
                                        focalLength * focalLength
                                                - 2 * focalLength * thickness / 3))
                / 2;
    }

    private static PolygonMesh lens(float z, float aperture, float curvature, float thickness) {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        // Each spherical face has its own centre and concentric rings, joined at the rim.
        for (int side = 0; side < 2; side++) {
            int base = vertices.size();
            float sign = side == 0 ? -1 : 1;
            vertices.add(
                    new PolygonMesh.Vertex(base, new Vec3(0, AXIS_Y, z + sign * thickness / 2)));
            for (int r = 1; r <= RINGS; r++) {
                float radius = aperture * r / RINGS;
                float sag = curvature - (float) Math.sqrt(curvature * curvature - radius * radius);
                ring(vertices, radius, z + sign * (thickness / 2 - sag));
            }
            cap(faces, base, side == 0);
            for (int r = 0; r < RINGS - 1; r++) {
                for (int i = 0; i < SIDES; i++) {
                    long a = base + 1 + r * SIDES + i;
                    long b = base + 1 + r * SIDES + (i + 1) % SIDES;
                    face(faces, side == 0, a, a + SIDES, b + SIDES);
                    face(faces, side == 0, a, b + SIDES, b);
                }
            }
        }
        int lastBack = 1 + (RINGS - 1) * SIDES;
        int lastFront = 1 + RINGS * SIDES + lastBack;
        join(faces, lastBack, lastFront);
        return PolygonMesh.closedSolid(vertices, faces, vertices.size(), faces.size());
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
