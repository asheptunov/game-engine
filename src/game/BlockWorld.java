package game;

import engine.BoxGeometry;
import engine.Material;
import engine.SceneInstance;
import engine.Transform;
import engine.WorldSnapshot;
import engine.lights.PointLight;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** The game retains block coordinates/types; engine instances are a derived representation. */
public final class BlockWorld {
    public enum Type {
        GROUND(0x8a94a4),
        RED(0xe65e52),
        GOLD(0xf7c653),
        BLUE(0x548fea),
        GREEN(0x68c58b);

        private final Material material;

        Type(int color) {
            material = Material.srgb(name().toLowerCase(java.util.Locale.ROOT), color);
        }
    }

    /** Integer coordinates identify block centers; each block is one world unit wide. */
    public record Block(int x, int y, int z, Type type) {}

    private final List<Block> blocks;

    public BlockWorld(List<Block> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    public List<Block> blocks() {
        return blocks;
    }

    public WorldSnapshot snapshot() {
        var instances = new ArrayList<SceneInstance>();
        for (var block : blocks) {
            instances.add(
                    new SceneInstance(
                            "block " + block.x + "," + block.y + "," + block.z,
                            BoxGeometry.UNIT,
                            new Transform(
                                    new Vec3(block.x, block.y, block.z),
                                    Vec3.ZERO,
                                    new Vec3(.5f, .5f, .5f)),
                            block.type.material));
        }
        return WorldSnapshot.of(
                instances,
                List.of(
                        new PointLight(new Vec3(-3, 7, -3), new Vec3(1, .94f, .84f), 210),
                        new PointLight(new Vec3(5, 5, 9), new Vec3(.75f, .85f, 1), 130)));
    }

    public static BlockWorld gallery() {
        var blocks = new ArrayList<Block>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 11; z++) {
                blocks.add(new Block(x, -1, z, Type.GROUND));
            }
        }
        blocks.add(new Block(-2, 0, 1, Type.RED));
        blocks.add(new Block(2, 0, 3, Type.BLUE));
        blocks.add(new Block(2, 1, 3, Type.BLUE));
        blocks.add(new Block(-1, 2, 5, Type.GREEN));
        for (int y = 0; y < 4; y++) {
            blocks.add(new Block(-3, y, 9, Type.GOLD));
        }
        blocks.add(new Block(1, 0, 8, Type.RED));
        blocks.add(new Block(2, 0, 8, Type.GREEN));
        blocks.add(new Block(3, 0, 8, Type.BLUE));
        return new BlockWorld(blocks);
    }
}
