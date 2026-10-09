package game;

import engine.CubeTextures;
import engine.Material;
import engine.Texture2D;

import math.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

/** Decode assets in the application; only immutable linear reflectance enters the engine. */
public final class GameTextures {
    private GameTextures() {}

    public static Texture2D load(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("Unsupported image format");
            }
            return new Texture2D(
                    image.getWidth(),
                    image.getHeight(),
                    image.getRGB(
                            0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Cannot load game texture " + path.toAbsolutePath() + ": " + error.getMessage(),
                    error);
        }
    }

    public static Map<BlockWorld.Type, Material> loadMaterials(Path directory) {
        var dirt = load(directory.resolve("dirt.png"));
        var grass =
                new CubeTextures(
                        load(directory.resolve("grass-top.png")),
                        dirt,
                        load(directory.resolve("grass-side.png")));
        var stone = load(directory.resolve("stone.png"));
        var woodEnd = load(directory.resolve("wood-end.png"));
        var wood = new CubeTextures(woodEnd, woodEnd, load(directory.resolve("wood-side.png")));
        var result = new EnumMap<BlockWorld.Type, Material>(BlockWorld.Type.class);
        result.put(BlockWorld.Type.GRASS, material("grass", grass));
        result.put(BlockWorld.Type.DIRT, material("dirt", new CubeTextures(dirt, dirt, dirt)));
        result.put(BlockWorld.Type.STONE, material("stone", new CubeTextures(stone, stone, stone)));
        result.put(BlockWorld.Type.WOOD, material("wood", wood));
        return Map.copyOf(result);
    }

    private static Material material(String name, CubeTextures textures) {
        return new Material(name, new Vec3(1, 1, 1)).withTextures(textures);
    }
}
