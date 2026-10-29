package game;

import engine.Material;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.List;
import java.util.Random;

/** Abandoned concrete shells: real window gaps and doorways remain usable firing positions. */
final class OvergrownRuins {
    private static final Material CONCRETE =
            new Material("Damp concrete", new Vec3(.28f, .30f, .25f));
    private static final Material RUST =
            new Material("Oxidized steel", new Vec3(.26f, .10f, .035f));
    private static final Material MOSS =
            new Material("Reclaimed masonry", new Vec3(.13f, .20f, .06f));
    private static final float[][] SITES = {{-11, 19}, {17, 50}, {-23, 75}};

    private OvergrownRuins() {}

    static boolean insideBuilding(float x, float z) {
        for (var site : SITES) {
            if (Math.abs(x - site[0]) < 6 && Math.abs(z - site[1]) < 7) {
                return true;
            }
        }
        return false;
    }

    static void populate(List<SceneInstance> objects) {
        for (int i = 0; i < SITES.length; i++) {
            var site = SITES[i];
            building(
                    objects,
                    "Abandoned station " + i + "/",
                    new Vec3(site[0], AdventureWorld.height(site[0], site[1]) - .15f, site[1]));
        }
        var random = new Random(623);
        for (int i = 0; i < 48; i++) {
            float z = i * 2.2f;
            float x = 9 * (float) Math.sin(z * .035);
            box(
                    objects,
                    "Broken access road " + i,
                    new Vec3(x, AdventureWorld.height(x, z) - .14f, z),
                    new Vec3(.9f + random.nextFloat() * .4f, .12f, .85f),
                    CONCRETE,
                    new Vec3(0, random.nextFloat() * 20 - 10, 0));
        }
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            float x = 20 + (float) Math.cos(angle) * 7;
            float z = 28 + (float) Math.sin(angle) * 7;
            float height = i % 3 == 0 ? 3 : 6;
            Foliage.hanging(
                    objects,
                    "column " + i,
                    new Vec3(x + .65f, AdventureWorld.height(x, z) + height, z),
                    height * .9f);
        }
    }

    private static void building(List<SceneInstance> objects, String name, Vec3 base) {
        // Independent short wall sections leave doors at both ends, even on sloping terrain.
        for (int side : new int[] {-1, 1}) {
            for (int bay = 0; bay < 3; bay++) {
                float z = -4 + bay * 4;
                piece(
                        objects,
                        name + "pier " + side + "/" + bay,
                        base,
                        new Vec3(side * 4, 2, z),
                        new Vec3(.35f, 2, .35f),
                        CONCRETE);
                if (bay < 2) {
                    piece(
                            objects,
                            name + "sill " + side + "/" + bay,
                            base,
                            new Vec3(side * 4, .65f, z + 2),
                            new Vec3(.25f, .65f, 1.7f),
                            MOSS);
                    piece(
                            objects,
                            name + "lintel " + side + "/" + bay,
                            base,
                            new Vec3(side * 4, 3.6f, z + 2),
                            new Vec3(.3f, .35f, 1.7f),
                            CONCRETE);
                }
                Foliage.hanging(
                        objects,
                        name + "ivy " + side + "/" + bay,
                        base.add(new Vec3(side * 4.35f, 4, z)),
                        3.6f);
            }
            piece(
                    objects,
                    name + "door jamb " + side,
                    base,
                    new Vec3(side * 2.8f, 1.4f, -4),
                    new Vec3(1.15f, 1.4f, .3f),
                    CONCRETE);
            piece(
                    objects,
                    name + "roof spar " + side,
                    base,
                    new Vec3(0, 4, side * 3.5f),
                    new Vec3(4.5f, .10f, .13f),
                    RUST);
            for (int bar = 0; bar < 5; bar++) {
                piece(
                        objects,
                        name + "exposed rebar " + side + "/" + bar,
                        base,
                        new Vec3(side * 3.5f, 3 + bar * .1f, -3 + bar * 1.5f),
                        new Vec3(.035f, 1.2f, .035f),
                        RUST);
            }
        }
        box(
                objects,
                name + "collapsed roof",
                base.add(new Vec3(-1.7f, .8f, 2)),
                new Vec3(2, .18f, 2.4f),
                MOSS,
                new Vec3(0, 18, 23));
        for (int i = 0; i < 9; i++) {
            float x = -5 + i * 1.2f;
            box(
                    objects,
                    name + "rubble " + i,
                    base.add(new Vec3(x, .2f, 5 + i % 3)),
                    new Vec3(.45f, .3f, .5f),
                    CONCRETE,
                    new Vec3(i * 7, i * 31, i * 13));
        }
        Foliage.hanging(objects, name + "roof creeper", base.add(new Vec3(1, 4.1f, -3.5f)), 3);
    }

    private static void piece(
            List<SceneInstance> objects,
            String name,
            Vec3 base,
            Vec3 offset,
            Vec3 half,
            Material material) {
        box(objects, name, base.add(offset), half, material, Vec3.ZERO);
    }

    private static void box(
            List<SceneInstance> objects,
            String name,
            Vec3 position,
            Vec3 half,
            Material material,
            Vec3 rotation) {
        objects.add(
                new SceneInstance(
                        name,
                        SceneInstance.box(),
                        new Transform(position, rotation, half),
                        material));
    }
}
