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
        GRASS,
        DIRT,
        STONE,
        WOOD,
        LEAVES;
    }

    /** Integer coordinates identify block centers; each block is one world unit wide. */
    public record Block(int x, int y, int z, Type type, boolean demonstration) {
        public Block(int x, int y, int z, Type type) {
            this(x, y, z, type, false);
        }
    }

    private final List<Block> blocks;
    private final java.util.Map<Type, Material> materials;

    public BlockWorld(List<Block> blocks) {
        this.blocks = List.copyOf(blocks);
        materials = GameTextures.loadMaterials(java.nio.file.Path.of("assets/game"));
    }

    public List<Block> blocks() {
        return blocks;
    }

    public WorldSnapshot snapshot() {
        var instances = new ArrayList<SceneInstance>();
        for (var block : blocks) {
            boolean demonstration = block.demonstration;
            instances.add(
                    new SceneInstance(
                            "block " + block.x + "," + block.y + "," + block.z,
                            BoxGeometry.UNIT,
                            new Transform(
                                    new Vec3(block.x, block.y, block.z),
                                    demonstration ? new Vec3(15, 35, 0) : Vec3.ZERO,
                                    demonstration
                                            ? new Vec3(.65f, .8f, .4f)
                                            : new Vec3(.5f, .5f, .5f)),
                            materials.get(block.type)));
        }
        return WorldSnapshot.of(
                instances,
                List.of(
                        new PointLight(new Vec3(-3, 7, -3), new Vec3(1, .94f, .84f), 210),
                        new PointLight(new Vec3(5, 5, 9), new Vec3(.75f, .85f, 1), 130),
                        // Low warm fill makes the elevated grass block's dirt underside
                        // inspectable.
                        new PointLight(new Vec3(-1, .2f, 4), new Vec3(1, .85f, .7f), 8)));
    }

    public static BlockWorld gallery() {
        var blocks = new ArrayList<Block>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 11; z++) {
                blocks.add(new Block(x, -1, z, Type.GRASS));
            }
        }
        blocks.add(new Block(-2, 0, 1, Type.DIRT));
        blocks.add(new Block(2, 0, 3, Type.STONE));
        blocks.add(new Block(2, 1, 3, Type.STONE));
        blocks.add(new Block(-1, 2, 5, Type.GRASS));
        for (int y = 0; y < 4; y++) {
            blocks.add(new Block(-3, y, 9, Type.WOOD));
        }
        blocks.add(new Block(1, 0, 8, Type.DIRT));
        blocks.add(new Block(2, 0, 8, Type.GRASS));
        blocks.add(new Block(3, 0, 8, Type.WOOD, true));
        return new BlockWorld(blocks);
    }

    public static BlockWorld landscape() {
        var blocks = new ArrayList<Block>();
        addGround(blocks);
        addTree(blocks);
        blocks.add(new Block(-5, 0, 5, Type.STONE));
        blocks.add(new Block(-4, 0, 5, Type.STONE));
        blocks.add(new Block(-5, 1, 5, Type.STONE));
        addShelter(blocks);
        return new BlockWorld(blocks);
    }

    private static void addGround(List<Block> blocks) {
        for (int x = -6; x < 6; x++) {
            for (int z = 0; z < 12; z++) {
                int top = groundHeight(z);
                for (int y = -1; y <= top; y++) {
                    blocks.add(new Block(x, y, z, y == top ? Type.GRASS : Type.DIRT));
                }
            }
        }
    }

    private static int groundHeight(int z) {
        if (z >= 9) {
            return 1;
        }
        return z >= 7 ? 0 : -1;
    }

    private static void addTree(List<Block> blocks) {
        // Tree on the lower lawn; the opaque canopy uses the shared grass-top texture.
        for (int y = 0; y <= 3; y++) {
            blocks.add(new Block(-3, y, 3, Type.WOOD));
        }
        for (int x = -4; x <= -2; x++) {
            for (int z = 2; z <= 4; z++) {
                blocks.add(new Block(x, 4, z, Type.LEAVES));
                if (x == -3 || z == 3) {
                    blocks.add(new Block(x, 5, z, Type.LEAVES));
                }
            }
        }
    }

    private static void addShelter(List<Block> blocks) {
        // Small open-front shelter, clear of the stepped bank.
        for (int x = 1; x <= 4; x++) {
            for (int z = 3; z <= 6; z++) {
                blocks.add(new Block(x, 0, z, Type.STONE));
                blocks.add(new Block(x, 3, z, Type.WOOD));
                if (x == 1 || x == 4 || z == 6) {
                    blocks.add(new Block(x, 1, z, Type.WOOD));
                    blocks.add(new Block(x, 2, z, Type.WOOD));
                }
            }
        }
    }
}
