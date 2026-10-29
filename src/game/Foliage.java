package game;

import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared thin leaf geometry: gaps are real geometry, not alpha-tested cards. */
final class Foliage {
    private static final PolygonMesh FERN = plant(false);
    private static final PolygonMesh SHRUB = plant(true);
    private static final PolygonMesh GRASS = grass();
    static final PolygonMesh PINE = pine();
    static final PolygonMesh CANOPY = canopy();
    private static final PolygonMesh VINE = vine();
    private static final Material[] LEAVES = {
        new Material("Fern green", new Vec3(.26f, .62f, .11f), Material.Kind.THIN_LEAF)
                .withLeaf(optics(.24f)),
        new Material("Olive foliage", new Vec3(.44f, .64f, .16f), Material.Kind.THIN_LEAF)
                .withLeaf(optics(.32f)),
        new Material("Forest grass", new Vec3(.38f, .60f, .14f), Material.Kind.THIN_LEAF)
                .withLeaf(optics(.14f))
    };

    private Foliage() {}

    static engine.LeafOptics optics(float thicknessMm) {
        return new engine.LeafOptics(.6f, new Vec3(.12f, .55f, .035f), thicknessMm, .65f, 24);
    }

    static void populate(List<SceneInstance> objects) {
        var random = new Random(87651);
        for (int i = 0; i < 1900; i++) {
            float x = random.nextFloat() * 125 - 62.5f;
            float z = random.nextFloat() * 130 - 22;
            // Keep the footpath legible, with denser low cover along both sides.
            if (Math.abs(x - 9 * Math.sin(z * .035)) < 1.25) {
                continue;
            }
            boolean shrub = i % 3 == 0;
            var mesh = shrub ? SHRUB : i % 3 == 1 ? FERN : GRASS;
            float scale = 1.1f + random.nextFloat() * 1.5f;
            objects.add(
                    new SceneInstance(
                            "Foliage " + i,
                            mesh,
                            new Transform(
                                    new Vec3(x, AdventureWorld.height(x, z), z),
                                    new Vec3(0, random.nextFloat() * 360, 0),
                                    new Vec3(scale, scale * .8f, scale)),
                            LEAVES[i % LEAVES.length]));
        }
        // Nearby concealment is reachable from spawn without crossing the open valley.
        for (int i = 0; i < 72; i++) {
            float z = -4 + i * 1.4f;
            float x = 9 * (float) Math.sin(z * .035) + (i % 2 == 0 ? -1 : 1) * (2.8f + i % 3 * .6f);
            objects.add(
                    new SceneInstance(
                            "Foliage approach " + i,
                            SHRUB,
                            new Transform(
                                    new Vec3(x, AdventureWorld.height(x, z), z),
                                    new Vec3(0, i * 37, 0),
                                    new Vec3(1.8f, 1.35f, 1.8f)),
                            LEAVES[i % 2]));
        }
    }

    static void hanging(List<SceneInstance> objects, String name, Vec3 top, float length) {
        objects.add(
                new SceneInstance(
                        "Foliage vine " + name,
                        VINE,
                        new Transform(top, Vec3.ZERO, new Vec3(.7f, length, .7f)),
                        LEAVES[1]));
    }

    private static PolygonMesh canopy() {
        var mesh = new Leaves();
        var random = new Random(1844);
        for (int i = 0; i < 180; i++) {
            double angle = i * 2.39996;
            float radius = (float) Math.sqrt(random.nextFloat());
            var root =
                    new Vec3(
                            (float) Math.cos(angle) * radius,
                            .25f * (1 - radius) + random.nextFloat() * .18f,
                            (float) Math.sin(angle) * radius);
            var direction =
                    new Vec3((float) Math.cos(angle + .7), -.1f, (float) Math.sin(angle + .7));
            mesh.leaf(
                    root,
                    root.add(direction.scale(.30f + random.nextFloat() * .15f)),
                    new Vec3(-direction.z() * .13f, .035f, direction.x() * .13f));
        }
        return mesh.finish();
    }

    private static PolygonMesh vine() {
        var mesh = new Leaves();
        for (int i = 0; i < 32; i++) {
            float y = -i / 32f;
            var root = new Vec3((float) Math.sin(i * .6) * .12f, y, 0);
            float side = i % 2 == 0 ? 1 : -1;
            mesh.leaf(
                    root,
                    root.add(new Vec3(side * .28f, -.055f, .08f)),
                    new Vec3(.02f, .025f, .12f));
        }
        return mesh.finish();
    }

    private static PolygonMesh plant(boolean shrub) {
        var mesh = new Leaves();
        float reach = shrub ? .8f : 1.1f;
        float leafLength = shrub ? .42f : .38f;
        float width = shrub ? .12f : .055f;
        for (int branch = 0; branch < 12; branch++) {
            double angle = branch * 2.39996;
            var radial = new Vec3((float) Math.cos(angle), 0, (float) Math.sin(angle));
            var side = new Vec3(-radial.z(), 0, radial.x());
            for (int leaf = 1; leaf <= 7; leaf++) {
                float t = leaf / 8f;
                float y =
                        shrub
                                ? .2f + branch % 4 * .23f + .18f * t
                                : .9f * (float) Math.sin(t * 2.2);
                var stem = radial.scale(t * reach).add(new Vec3(0, y, 0));
                float length = (1 - t) * leafLength + .06f;
                for (int sign : new int[] {-1, 1}) {
                    var end = stem.add(side.scale(sign * length)).add(radial.scale(.12f));
                    mesh.leaf(stem, end, radial.scale(width));
                }
            }
        }
        return mesh.finish();
    }

    private static PolygonMesh grass() {
        var mesh = new Leaves();
        var random = new Random(13);
        for (int i = 0; i < 70; i++) {
            double angle = i * 2.39996;
            var base = new Vec3(random.nextFloat() - .5f, 0, random.nextFloat() - .5f);
            var tip =
                    base.add(
                            new Vec3(
                                    (float) Math.cos(angle) * .24f,
                                    .35f + random.nextFloat() * .5f,
                                    (float) Math.sin(angle) * .24f));
            mesh.leaf(
                    base,
                    tip,
                    new Vec3((float) Math.sin(angle) * .025f, 0, (float) -Math.cos(angle) * .025f));
        }
        return mesh.finish();
    }

    private static PolygonMesh pine() {
        var mesh = new Leaves();
        for (int branch = 0; branch < 32; branch++) {
            double angle = branch * 2.39996;
            float y = branch / 32f;
            var radial = new Vec3((float) Math.cos(angle), 0, (float) Math.sin(angle));
            var side = new Vec3(-radial.z(), 0, radial.x());
            for (int spray = 1; spray <= 5; spray++) {
                float t = spray / 5f;
                var stem = new Vec3(0, y, 0).add(radial.scale((1 - y) * t));
                for (int sign : new int[] {-1, 1}) {
                    var tip = stem.add(side.scale(sign * .24f * (1 - y))).add(new Vec3(0, .1f, 0));
                    mesh.leaf(stem, tip, radial.scale(.13f * (1 - y) + .01f));
                }
            }
        }
        return mesh.finish();
    }

    private static final class Leaves {
        private final List<PolygonMesh.Vertex> vertices = new ArrayList<>();
        private final List<PolygonMesh.Face> faces = new ArrayList<>();

        void leaf(Vec3 root, Vec3 tip, Vec3 width) {
            var middle = root.add(tip).scale(.5f);
            long first = vertices.size();
            for (var point :
                    List.of(
                            root,
                            middle.add(width),
                            tip,
                            middle.sub(width),
                            middle.add(new Vec3(0, .025f, 0)))) {
                vertices.add(new PolygonMesh.Vertex(vertices.size(), point));
            }
            for (int edge = 0; edge < 4; edge++) {
                faces.add(
                        new PolygonMesh.Face(
                                faces.size(),
                                List.of(first + edge, first + (edge + 1) % 4, first + 4)));
            }
        }

        PolygonMesh finish() {
            return PolygonMesh.surface(vertices, faces, vertices.size(), faces.size());
        }
    }
}
