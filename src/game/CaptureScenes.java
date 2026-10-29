package game;

import engine.SceneInstance;
import engine.Transform;

import math.Vec3;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Shared assets and exact per-slot animation deltas for capture version two. */
final class CaptureScenes {
    static final int MAX_SCENES =
            GameRecording.MAX_MOTIONS + GameRecording.MAX_STATES + GameRecording.MAX_PICTURES + 2;
    private static final int MAX_INSTANCES = 100_000;
    // Bound restored scene-list storage separately from decoded asset/delta bytes.
    private static final long MAX_REFERENCES = 16_000_000;

    private CaptureScenes() {}

    static void save(Path directory, List<List<SceneInstance>> scenes, CaptureCodec.Budget budget)
            throws IOException {
        var catalog = new CaptureScene.Catalog();
        List<SceneInstance> previous = List.of();
        long references = 0;
        try (var output =
                new DataOutputStream(
                        new GZIPOutputStream(
                                Files.newOutputStream(directory.resolve("scenes.bin.gz"))))) {
            for (var scene : scenes) {
                references += scene.size();
                checkSize(scene.size(), references);
                budget.consume(4);
                output.writeInt(scene.size());
                for (int i = 0; i < scene.size(); i++) {
                    var instance = scene.get(i);
                    boolean changed = i >= previous.size() || !instance.equals(previous.get(i));
                    budget.consume(1);
                    output.writeBoolean(changed);
                    if (changed) {
                        var value = catalog.capture(instance);
                        budget.consume(46L + 3L * value.name().length());
                        output.writeUTF(value.name());
                        output.writeInt(value.shape());
                        output.writeInt(value.surface());
                        vector(output, value.position());
                        vector(output, value.rotation());
                        vector(output, value.scale());
                    }
                }
                previous = scene;
            }
        }
        CaptureCodec.save(directory.resolve("assets.properties.gz"), catalog.assets(), budget);
    }

    static List<List<SceneInstance>> load(Path directory, int count, CaptureCodec.Budget budget)
            throws IOException {
        var assets =
                CaptureCodec.load(
                        directory.resolve("assets.properties.gz"), CaptureScene.class, budget);
        var geometry = assets.shapes().stream().map(CaptureScene.Shape::geometry).toList();
        var materials = assets.surfaces().stream().map(CaptureScene.Surface::restored).toList();
        var scenes = new ArrayList<List<SceneInstance>>();
        List<SceneInstance> previous = List.of();
        long references = 0;
        try (var input =
                new DataInputStream(
                        new GZIPInputStream(
                                Files.newInputStream(directory.resolve("scenes.bin.gz"))))) {
            for (int s = 0; s < count; s++) {
                budget.consume(4);
                int size = input.readInt();
                references += size;
                checkSize(size, references);
                var scene = new ArrayList<SceneInstance>(size);
                for (int i = 0; i < size; i++) {
                    budget.consume(1);
                    if (input.readBoolean()) {
                        scene.add(instance(input, budget, geometry, materials));
                    } else {
                        if (i >= previous.size()) {
                            throw new IOException("Missing capture instance delta");
                        }
                        scene.add(previous.get(i));
                    }
                }
                previous = List.copyOf(scene);
                scenes.add(previous);
            }
            if (input.read() != -1) {
                throw new IOException("Trailing capture scene data");
            }
        }
        return List.copyOf(scenes);
    }

    private static SceneInstance instance(
            DataInputStream input,
            CaptureCodec.Budget budget,
            List<engine.GeometryData> geometry,
            List<engine.Material> materials)
            throws IOException {
        String name = input.readUTF();
        budget.consume(46L + 3L * name.length());
        int shape = input.readInt();
        int surface = input.readInt();
        if (shape < 0 || shape >= geometry.size() || surface < 0 || surface >= materials.size()) {
            throw new IOException("Invalid capture asset reference");
        }
        return new SceneInstance(
                name,
                geometry.get(shape),
                new Transform(vector(input), vector(input), vector(input)),
                materials.get(surface));
    }

    private static void checkSize(int size, long references) throws IOException {
        if (size < 0 || size > MAX_INSTANCES || references > MAX_REFERENCES) {
            throw new IOException("Capture scene instances exceed bounded storage");
        }
    }

    private static void vector(DataOutputStream output, Vec3 vector) throws IOException {
        output.writeFloat(vector.x());
        output.writeFloat(vector.y());
        output.writeFloat(vector.z());
    }

    private static Vec3 vector(DataInputStream input) throws IOException {
        var value = new Vec3(input.readFloat(), input.readFloat(), input.readFloat());
        if (!Float.isFinite(value.x())
                || !Float.isFinite(value.y())
                || !Float.isFinite(value.z())) {
            throw new IOException("Nonfinite capture transform");
        }
        return value;
    }
}
