package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Rounded uniform panels with geometry-based woodland camouflage shared by every patrol. */
final class PatrolMesh {
    private static final Material[] CAMO = {
        new Material("Woodland olive", new Vec3(.19f, .25f, .085f)),
        new Material("Woodland moss", new Vec3(.09f, .16f, .05f)),
        new Material("Woodland earth", new Vec3(.20f, .12f, .055f)),
        new Material("Woodland shadow", new Vec3(.035f, .045f, .025f))
    };
    private static final Material SKIN = new Material("Skin", new Vec3(.48f, .31f, .19f));
    private static final Material KIT =
            new Material("Webbing and boots", new Vec3(.055f, .065f, .035f));
    private static final List<PolygonMesh> PANELS = panels();
    private static final PolygonMesh ROUND = rounded();

    private PatrolMesh() {}

    static void uniform(List<SceneInstance> result, String name, Vec3 center, Vec3 half) {
        var pose = new Transform(center, Vec3.ZERO, half);
        for (int i = 0; i < PANELS.size(); i++) {
            result.add(new SceneInstance(name + "/camo" + i, PANELS.get(i), pose, CAMO[i]));
        }
    }

    static void detail(List<SceneInstance> result, String name, Vec3 feet, double gait) {
        piece(
                result,
                name + "face",
                feet.add(new Vec3(0, 1.75f, 0)),
                new Vec3(.17f, .22f, .18f),
                SKIN);
        uniform(
                result,
                name + "helmet",
                feet.add(new Vec3(0, 1.88f, 0)),
                new Vec3(.19f, .10f, .19f));
        piece(
                result,
                name + "vest",
                feet.add(new Vec3(0, 1.18f, -.17f)),
                new Vec3(.25f, .29f, .065f),
                KIT);
        uniform(
                result,
                name + "pack",
                feet.add(new Vec3(0, 1.23f, .22f)),
                new Vec3(.23f, .30f, .12f));
        for (int side : new int[] {-1, 1}) {
            float swing = (float) Math.sin(gait) * side * .13f;
            piece(
                    result,
                    name + "boot" + side,
                    feet.add(new Vec3(side * .16f, .09f, -.055f + swing)),
                    new Vec3(.13f, .09f, .21f),
                    KIT);
            piece(
                    result,
                    name + "pouch" + side,
                    feet.add(new Vec3(side * .15f, 1.1f, -.25f)),
                    new Vec3(.08f, .105f, .05f),
                    CAMO[0]);
            piece(
                    result,
                    name + "knee" + side,
                    feet.add(new Vec3(side * .16f, .45f, -.14f + swing * .35f)),
                    new Vec3(.10f, .12f, .035f),
                    KIT);
            piece(
                    result,
                    name + "ear" + side,
                    feet.add(new Vec3(side * .18f, 1.77f, 0)),
                    new Vec3(.035f, .06f, .045f),
                    KIT);
        }
    }

    private static void piece(
            List<SceneInstance> result, String name, Vec3 center, Vec3 half, Material material) {
        result.add(
                new SceneInstance(name, ROUND, new Transform(center, Vec3.ZERO, half), material));
    }

    private static List<PolygonMesh.Vertex> vertices() {
        var vertices = new ArrayList<PolygonMesh.Vertex>();
        for (int ring = 0; ring <= 8; ring++) {
            float y = -1 + ring * .25f;
            float radius = ring == 0 || ring == 8 ? .5f : ring == 1 || ring == 7 ? .85f : 1;
            for (int side = 0; side < 16; side++) {
                double angle = side * Math.PI / 8;
                vertices.add(
                        new PolygonMesh.Vertex(
                                vertices.size(),
                                new Vec3(
                                        (float) Math.cos(angle) * radius,
                                        y,
                                        (float) Math.sin(angle) * radius)));
            }
        }
        return vertices;
    }

    private static List<PolygonMesh.Face> faces(int color) {
        var faces = new ArrayList<PolygonMesh.Face>();
        for (int ring = 0; ring < 8; ring++) {
            for (int side = 0; side < 16; side++) {
                // Multi-scale irregular patches wrap around the limb, avoiding a checkerboard.
                int patch = Math.floorMod((side / 3) * 13 + (ring / 2) * 7 + (side + ring) / 5, 4);
                if (color >= 0 && patch != color) {
                    continue;
                }
                long a = ring * 16L + side;
                long b = ring * 16L + (side + 1) % 16;
                faces.add(new PolygonMesh.Face(faces.size(), List.of(a, a + 16, b + 16, b)));
            }
        }
        if (color <= 0) {
            var bottom = new ArrayList<Long>();
            var top = new ArrayList<Long>();
            for (long side = 0; side < 16; side++) {
                bottom.add(side);
                top.add(143 - side);
            }
            faces.add(new PolygonMesh.Face(faces.size(), bottom));
            faces.add(new PolygonMesh.Face(faces.size(), top));
        }
        return faces;
    }

    private static List<PolygonMesh> panels() {
        var result = new ArrayList<PolygonMesh>();
        var vertices = vertices();
        for (int color = 0; color < 4; color++) {
            var faces = faces(color);
            var used = new java.util.HashSet<Long>();
            for (var face : faces) {
                used.addAll(face.vertexIds());
            }
            result.add(
                    PolygonMesh.surface(
                            vertices.stream().filter(v -> used.contains(v.id())).toList(),
                            faces,
                            vertices.size(),
                            faces.size()));
        }
        return List.copyOf(result);
    }

    private static PolygonMesh rounded() {
        var vertices = vertices();
        var faces = faces(-1);
        return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
    }
}
