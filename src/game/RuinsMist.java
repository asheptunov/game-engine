package game;

import engine.FogRegion;
import engine.Material;
import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Reproducible local-air demonstration at the existing ruins, centered on (20, 3, 28). */
public final class RuinsMist {
    private RuinsMist() {}

    public static FogRegion region(int index) {
        return new FogRegion(
                new Vec3(index == 0 ? 20 : 25, 3, 28),
                new Vec3(13, 5, 12),
                0,
                new Vec3(.035f, .035f, .035f),
                new Vec3(.002f, .002f, .002f),
                1,
                1.5f,
                .65f,
                .45f,
                2,
                .2f);
    }

    public static List<SceneInstance> scenery(
            List<SceneInstance> input, boolean demo, boolean closed, float shift) {
        var result = new ArrayList<SceneInstance>();
        for (var instance : input) {
            if (!instance.name().startsWith("Mist demo ")) {
                result.add(instance);
            }
        }
        if (demo) {
            var material = new Material("Ruins mist screen", new Vec3(.52f, .49f, .4f));
            result.add(screen("north", 24 + shift, 2, material));
            result.add(screen("south", 32 + shift, 2, material));
            if (closed) {
                result.add(screen("shutter", 28 + shift, 2, material));
            }
        }
        return List.copyOf(result);
    }

    private static SceneInstance screen(String name, float z, float halfWidth, Material material) {
        return new SceneInstance(
                "Mist demo " + name,
                SceneInstance.box(),
                new Transform(new Vec3(28, 6, z), Vec3.ZERO, new Vec3(.15f, 5, halfWidth)),
                material);
    }
}
