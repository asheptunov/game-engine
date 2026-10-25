package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;
import engine.WorldSnapshot;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, faceted valley in world units; immutable meshes are shared by scenery instances.
 */
public final class AdventureWorld {
    private AdventureWorld() {}

    public static WorldSnapshot create() {
        var objects = new ArrayList<SceneInstance>();
        var grass = new Material("Meadow", new Vec3(.28f, .42f, .13f));
        var stone = new Material("Weathered limestone", new Vec3(.52f, .49f, .40f));
        var bark = new Material("Pine bark", new Vec3(.23f, .12f, .06f));
        var needles = new Material("Pine needles", new Vec3(.08f, .24f, .12f));
        for (int x = -80; x < 80; x += 20) {
            for (int z = -40; z < 120; z += 20) {
                objects.add(
                        new SceneInstance(
                                "Meadow " + x + "," + z, terrain(x, z), Transform.IDENTITY, grass));
            }
        }
        var trunk = tapered(8, .65f);
        var crown = tapered(10, 0);
        var rock = tapered(7, .45f);
        var random = new Random(72641);
        for (int index = 0; index < 90; index++) {
            float x = random.nextFloat() * 140 - 70;
            float z = random.nextFloat() * 140 - 30;
            // Leave a winding meadow corridor and the ruins clearing open for exploration.
            if (Math.abs(x - 9 * Math.sin(z * .035)) < 9 || (x > 8 && x < 32 && z > 15 && z < 40)) {
                continue;
            }
            float y = height(x, z);
            float size = 3 + random.nextFloat() * 3;
            add(
                    objects,
                    "Trunk " + index,
                    trunk,
                    bark,
                    new Vec3(x, y, z),
                    new Vec3(.4f, size, .4f));
            for (int tier = 0; tier < 3; tier++) {
                float radius = size * (.55f - tier * .12f);
                add(
                        objects,
                        "Crown " + index + "/" + tier,
                        crown,
                        needles,
                        new Vec3(x, y + size * (.4f + tier * .3f), z),
                        new Vec3(radius, size * .8f, radius));
            }
        }
        for (int index = 0; index < 36; index++) {
            float x = random.nextFloat() * 130 - 65;
            float z = random.nextFloat() * 130 - 25;
            float size = .7f + random.nextFloat() * 2;
            add(
                    objects,
                    "Boulder " + index,
                    rock,
                    stone,
                    new Vec3(x, height(x, z) - .1f, z),
                    new Vec3(size, size * .8f, size * .7f));
        }
        var pillar = tapered(12, .85f);
        for (int index = 0; index < 8; index++) {
            double angle = index * Math.PI / 4;
            float x = 20 + (float) Math.cos(angle) * 7;
            float z = 28 + (float) Math.sin(angle) * 7;
            add(
                    objects,
                    "Ruins column " + index,
                    pillar,
                    stone,
                    new Vec3(x, height(x, z), z),
                    new Vec3(.8f, index % 3 == 0 ? 3 : 6, .8f));
        }
        return WorldSnapshot.of(objects, List.of());
    }

    private static void add(
            List<SceneInstance> objects,
            String name,
            PolygonMesh mesh,
            Material material,
            Vec3 position,
            Vec3 scale) {
        objects.add(
                new SceneInstance(name, mesh, new Transform(position, Vec3.ZERO, scale), material));
    }

    /** Low central valley with taller rolling terrain toward its edges. */
    public static float height(float x, float z) {
        double hills =
                Math.sin(x * .065) * Math.cos(z * .045) * 3 + Math.sin(z * .075 + x * .025) * 1.5;
        double edge = Math.pow(Math.abs(x) / 80, 2) * 12;
        return (float) (hills + edge - 1.5);
    }

    private static PolygonMesh terrain(int originX, int originZ) {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        for (int z = 0; z <= 10; z++) {
            for (int x = 0; x <= 10; x++) {
                float worldX = originX + x * 2;
                float worldZ = originZ + z * 2;
                vertices.add(
                        new PolygonMesh.Vertex(
                                vertices.size(), new Vec3(worldX, height(worldX, worldZ), worldZ)));
            }
        }
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 10; x++) {
                long first = z * 11L + x;
                faces.add(
                        new PolygonMesh.Face(faces.size(), List.of(first, first + 11, first + 1)));
                faces.add(
                        new PolygonMesh.Face(
                                faces.size(), List.of(first + 1, first + 11, first + 12)));
            }
        }
        return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
    }

    /** Upright unit-height polygon cone or tapered column with outward-facing triangles. */
    private static PolygonMesh tapered(int sides, float topRadius) {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        var faces = new ArrayList<PolygonMesh.Face>();
        for (int index = 0; index < sides; index++) {
            double angle = index * 2 * Math.PI / sides;
            vertices.add(
                    new PolygonMesh.Vertex(
                            index, new Vec3((float) Math.cos(angle), 0, (float) Math.sin(angle))));
        }
        if (topRadius == 0) {
            vertices.add(new PolygonMesh.Vertex(sides, new Vec3(0, 1, 0)));
        } else {
            for (int index = 0; index < sides; index++) {
                var base = vertices.get(index).position();
                vertices.add(
                        new PolygonMesh.Vertex(
                                sides + index,
                                new Vec3(base.x() * topRadius, 1, base.z() * topRadius)));
            }
        }
        var bottom = new ArrayList<Long>();
        var top = new ArrayList<Long>();
        for (long index = 0; index < sides; index++) {
            long next = (index + 1) % sides;
            bottom.add(index);
            if (topRadius == 0) {
                faces.add(new PolygonMesh.Face(faces.size(), List.of(index, (long) sides, next)));
            } else {
                faces.add(
                        new PolygonMesh.Face(
                                faces.size(), List.of(index, index + sides, next + sides, next)));
                top.add(sides * 2L - 1 - index);
            }
        }
        faces.add(new PolygonMesh.Face(faces.size(), bottom));
        if (topRadius != 0) {
            faces.add(new PolygonMesh.Face(faces.size(), top));
        }
        return PolygonMesh.closedSolid(vertices, faces, vertices.size(), faces.size());
    }
}
