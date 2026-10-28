package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared metre-scale rifle assets. Local +Z points down the barrel, +Y is up. */
final class RifleMesh {
    private static final Material STOCK =
            new Material("Walnut stock", new Vec3(.24f, .105f, .042f));
    private static final Material METAL = new Material("Blued steel", new Vec3(.065f, .085f, .1f));
    private static final Material RUBBER =
            new Material("Rubber and recesses", new Vec3(.018f, .022f, .025f));
    private static final Material EDGE = new Material("Machined edges", new Vec3(.24f, .29f, .32f));
    private static final List<SceneInstance> BODY = build();
    private static final List<SceneInstance> BOLT = bolt();

    private RifleMesh() {}

    static List<SceneInstance> instances(String prefix, Transform pose, double cycle) {
        return instances(prefix, pose, cycle, 25);
    }

    private static List<SceneInstance> instances(
            String prefix, Transform pose, double cycle, float focus) {
        var result = new ArrayList<SceneInstance>();
        for (var part : BODY) {
            result.add(
                    new SceneInstance(
                            prefix + part.name(), part.geometry(), pose, part.material()));
        }
        float pull = (float) Math.sin(Math.PI * Math.clamp(cycle / 1.25, 0, 1)) * .085f;
        var boltPose =
                Transform.compose(
                        pose, new Transform(new Vec3(0, 0, -pull), Vec3.ZERO, new Vec3(1, 1, 1)));
        for (var part : BOLT) {
            result.add(
                    new SceneInstance(
                            prefix + part.name(), part.geometry(), boltPose, part.material()));
        }
        result.addAll(ScopeMesh.instances(prefix + "scope/", pose, focus));
        return List.copyOf(result);
    }

    /** Camera-local metres, transformed into the same world space as all other geometry. */
    static Transform firstPersonPose(engine.Camera camera) {
        return firstPersonPose(camera, false);
    }

    static Transform firstPersonPose(engine.Camera camera, boolean scoped) {
        var forward = camera.forward();
        float yaw = (float) Math.toDegrees(Math.atan2(forward.x(), forward.z()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(forward.y(), -1, 1)));
        var cameraPose = new Transform(camera.eye(), new Vec3(pitch, yaw, 0), new Vec3(1, 1, 1));
        if (scoped) {
            return Transform.compose(
                    cameraPose,
                    new Transform(
                            new Vec3(
                                    0,
                                    -ScopeMesh.AXIS_Y,
                                    -ScopeMesh.EYEPIECE_Z + ScopeMesh.EYE_RELIEF),
                            Vec3.ZERO,
                            new Vec3(1, 1, 1)));
        }
        return Transform.compose(
                cameraPose,
                new Transform(
                        new Vec3(.24f, -.245f, .85f), new Vec3(0, -10, -3), new Vec3(1, 1, 1)));
    }

    static List<SceneInstance> firstPerson(engine.Camera camera, double cooldown) {
        return firstPerson(camera, cooldown, false);
    }

    static List<SceneInstance> firstPerson(engine.Camera camera, double cooldown, boolean scoped) {
        return instances(
                "player/rifle/",
                firstPersonPose(camera, scoped),
                cooldown,
                scoped ? camera.focus() : 25);
    }

    static Vec3 muzzle(engine.Camera camera, boolean scoped) {
        return firstPersonPose(camera, scoped).point(new Vec3(0, .018f, .687f));
    }

    private static List<SceneInstance> build() {
        var model = new Builder();
        model.stock(
                STOCK,
                new float[][] {
                    {-.53f, .045f, -.19f, -.02f}, {-.48f, .048f, -.19f, -.015f},
                    {-.27f, .036f, -.145f, -.025f}, {-.17f, .029f, -.1f, -.02f},
                    {-.08f, .037f, -.09f, -.015f}, {.12f, .038f, -.075f, -.015f},
                    {.34f, .026f, -.06f, -.018f}
                });
        model.box(RUBBER, new Vec3(0, -.105f, -.535f), new Vec3(.049f, .09f, .012f));
        model.box(STOCK, new Vec3(0, -.024f, -.36f), new Vec3(.04f, .025f, .105f));
        model.box(METAL, new Vec3(0, -.015f, -.04f), new Vec3(.034f, .028f, .14f));
        model.tube(METAL, new Vec3(0, .018f, .08f), .022f, .021f, .30f);
        model.tube(METAL, new Vec3(0, .018f, .38f), .021f, .016f, .28f);
        model.tube(EDGE, new Vec3(0, .018f, .66f), .021f, .021f, .025f);
        model.tube(RUBBER, new Vec3(0, .018f, .686f), .011f, .011f, .001f);
        model.box(METAL, new Vec3(0, -.11f, .045f), new Vec3(.027f, .043f, .047f));
        // Open trigger guard assembled from three rails, with a separate curved trigger.
        model.box(METAL, new Vec3(0, -.14f, -.1f), new Vec3(.013f, .009f, .052f));
        model.box(METAL, new Vec3(0, -.105f, -.15f), new Vec3(.013f, .035f, .008f));
        model.box(METAL, new Vec3(0, -.105f, -.05f), new Vec3(.013f, .035f, .008f));
        model.box(EDGE, new Vec3(0, -.1f, -.085f), new Vec3(.006f, .027f, .006f));
        model.box(RUBBER, new Vec3(.035f, .006f, -.035f), new Vec3(.002f, .014f, .055f));
        model.box(EDGE, new Vec3(0, .052f, -.01f), new Vec3(.026f, .008f, .145f));
        for (float z : new float[] {-.07f, .0f}) {
            model.box(METAL, new Vec3(0, .075f, z), new Vec3(.022f, .022f, .012f));
        }
        model.box(METAL, new Vec3(0, .16f, -.02f), new Vec3(.02f, .012f, .02f));
        for (int index = 0; index < 8; index++) {
            model.box(
                    EDGE, new Vec3(0, .061f, -.14f + index * .035f), new Vec3(.028f, .003f, .004f));
        }
        return model.finish("body/");
    }

    private static List<SceneInstance> bolt() {
        var model = new Builder();
        model.tube(EDGE, new Vec3(0, .022f, -.19f), .018f, .018f, .19f);
        model.box(EDGE, new Vec3(.055f, .006f, -.13f), new Vec3(.04f, .008f, .008f));
        model.box(EDGE, new Vec3(.09f, -.015f, -.13f), new Vec3(.008f, .025f, .008f));
        model.tube(RUBBER, new Vec3(.09f, -.04f, -.148f), .018f, .018f, .036f);
        return model.finish("bolt/");
    }

    private static final class Builder {
        private final Map<Material, Surface> surfaces = new LinkedHashMap<>();

        void box(Material material, Vec3 center, Vec3 half) {
            stock(
                    material,
                    new float[][] {
                        {
                            center.z() - half.z(),
                            half.x(),
                            center.y() - half.y(),
                            center.y() + half.y()
                        },
                        {
                            center.z() + half.z(),
                            half.x(),
                            center.y() - half.y(),
                            center.y() + half.y()
                        }
                    },
                    center.x());
        }

        void stock(Material material, float[][] stations) {
            stock(material, stations, 0);
        }

        void stock(Material material, float[][] stations, float x) {
            var rings = new ArrayList<List<Vec3>>();
            for (var s : stations) {
                float bevel = Math.min(s[1] * .3f, (s[3] - s[2]) * .2f);
                rings.add(
                        List.of(
                                new Vec3(x - s[1] + bevel, s[2], s[0]),
                                new Vec3(x + s[1] - bevel, s[2], s[0]),
                                new Vec3(x + s[1], s[2] + bevel, s[0]),
                                new Vec3(x + s[1], s[3] - bevel, s[0]),
                                new Vec3(x + s[1] - bevel, s[3], s[0]),
                                new Vec3(x - s[1] + bevel, s[3], s[0]),
                                new Vec3(x - s[1], s[3] - bevel, s[0]),
                                new Vec3(x - s[1], s[2] + bevel, s[0])));
            }
            surfaces.computeIfAbsent(material, key -> new Surface()).loft(rings);
        }

        void tube(Material material, Vec3 origin, float back, float front, float length) {
            var rings = new ArrayList<List<Vec3>>();
            for (int end = 0; end < 2; end++) {
                var ring = new ArrayList<Vec3>();
                for (int side = 0; side < 24; side++) {
                    double angle = side * Math.PI / 12;
                    float radius = end == 0 ? back : front;
                    ring.add(
                            origin.add(
                                    new Vec3(
                                            (float) Math.cos(angle) * radius,
                                            (float) Math.sin(angle) * radius,
                                            end * length)));
                }
                rings.add(ring);
            }
            surfaces.computeIfAbsent(material, key -> new Surface()).loft(rings);
        }

        List<SceneInstance> finish(String prefix) {
            var result = new ArrayList<SceneInstance>();
            for (var entry : surfaces.entrySet()) {
                result.add(
                        new SceneInstance(
                                prefix + result.size(),
                                entry.getValue().mesh(),
                                Transform.IDENTITY,
                                entry.getKey()));
            }
            return List.copyOf(result);
        }
    }

    private static final class Surface {
        private final List<PolygonMesh.Vertex> vertices = new ArrayList<>();
        private final List<PolygonMesh.Face> faces = new ArrayList<>();

        void loft(List<List<Vec3>> rings) {
            long first = vertices.size();
            int sides = rings.getFirst().size();
            for (var ring : rings) {
                for (var point : ring) {
                    vertices.add(new PolygonMesh.Vertex(vertices.size(), point));
                }
            }
            var cap = new ArrayList<Long>();
            for (int side = sides - 1; side >= 0; side--) {
                cap.add(first + side);
            }
            face(cap);
            for (int ring = 0; ring < rings.size() - 1; ring++) {
                for (int side = 0; side < sides; side++) {
                    long a = first + ring * sides + side;
                    long b = first + ring * sides + (side + 1) % sides;
                    face(List.of(a, b, b + sides, a + sides));
                }
            }
            cap = new ArrayList<>();
            for (int side = 0; side < sides; side++) {
                cap.add(first + (rings.size() - 1) * sides + side);
            }
            face(cap);
        }

        private void face(List<Long> ids) {
            faces.add(new PolygonMesh.Face(faces.size(), ids));
        }

        PolygonMesh mesh() {
            return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
        }
    }
}
