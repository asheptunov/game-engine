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
        var grass = new Material("Moss and forest litter", new Vec3(.12f, .16f, .065f));
        var stone = new Material("Moss-stained limestone", new Vec3(.31f, .34f, .25f));
        var bark = new Material("Pine bark", new Vec3(.23f, .12f, .06f));
        var needles = new Material("Pine needles", new Vec3(.08f, .24f, .12f));
        for (int x = -80; x < 80; x += 20) {
            for (int z = -40; z < 120; z += 20) {
                objects.add(
                        new SceneInstance(
                                "Meadow " + x + "," + z, terrain(x, z), Transform.IDENTITY, grass));
            }
        }
        var random = new Random(72641);
        forest(objects, bark, needles, random);
        var rock = tapered(7, .45f);
        for (int index = 0; index < 36; index++) {
            float x = random.nextFloat() * 130 - 65;
            float z = random.nextFloat() * 130 - 25;
            float size = .7f + random.nextFloat() * 2;
            if (Math.abs(x - 9 * Math.sin(z * .035)) < size + 2
                    || (Math.abs(x) < size + 1.5f && z > -10 && z < 18)) {
                continue;
            }
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
        OvergrownRuins.populate(objects);
        Foliage.populate(objects);
        return WorldSnapshot.of(objects, List.of());
    }

    private static void forest(
            List<SceneInstance> objects, Material bark, Material needles, Random random) {
        var trunk = tapered(12, .55f);
        var broadleaf =
                new Material("Jungle canopy", new Vec3(.24f, .56f, .14f), Material.Kind.THIN_LEAF)
                        .withLeaf(Foliage.optics(.35f));
        for (int index = 0; index < 380; index++) {
            float x = random.nextFloat() * 140 - 70;
            float z = random.nextFloat() * 140 - 30;
            if (clearRoute(x, z)) {
                continue;
            }
            float y = height(x, z);
            float size = 6 + random.nextFloat() * 6;
            add(
                    objects,
                    "Trunk " + index,
                    trunk,
                    bark,
                    new Vec3(x, y, z),
                    new Vec3(.35f + size * .025f, size, .35f + size * .025f));
            if (index % 4 == 0) {
                pineCrown(objects, needles, index, new Vec3(x, y, z), size);
            } else {
                for (int tier = 0; tier < 2; tier++) {
                    float radius = (4.5f - tier) + random.nextFloat() * 1.5f;
                    add(
                            objects,
                            "Foliage Canopy " + index + "/" + tier,
                            Foliage.CANOPY,
                            broadleaf,
                            new Vec3(x + tier * 1.2f, y + size * (.68f + tier * .22f), z),
                            new Vec3(radius, 3, radius));
                }
                if (index % 3 == 0) {
                    Foliage.hanging(
                            objects,
                            "tree " + index,
                            new Vec3(x + 2, y + size * .7f, z),
                            size * .5f);
                }
            }
        }
    }

    private static boolean clearRoute(float x, float z) {
        return Math.abs(x - 9 * Math.sin(z * .035)) < 2.5
                || (Math.abs(x) < 1.5f && z > -10 && z < 18)
                || (x > 11 && x < 29 && z > 19 && z < 37)
                || OvergrownRuins.insideBuilding(x, z);
    }

    private static void pineCrown(
            List<SceneInstance> objects, Material needles, int index, Vec3 base, float size) {
        for (int tier = 0; tier < 3; tier++) {
            float radius = size * (.5f - tier * .10f);
            add(
                    objects,
                    "Foliage Crown " + index + "/" + tier,
                    Foliage.PINE,
                    needles,
                    base.add(new Vec3(0, size * (.35f + tier * .25f), 0)),
                    new Vec3(radius, size * .65f, radius));
        }
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
