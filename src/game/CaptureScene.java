package game;

import engine.AnalyticSphere;
import engine.CubeTextures;
import engine.GeometryData;
import engine.Material;
import engine.PolygonMesh;
import engine.SceneInstance;
import engine.Texture2D;
import engine.Transform;

import math.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Embedded geometry/material/texture assets; no dependency on the built-in world generator. */
public record CaptureScene(List<Shape> shapes, List<Surface> surfaces, List<Instance> instances) {
    public record Shape(
            AnalyticSphere sphere,
            boolean closed,
            List<PolygonMesh.Vertex> vertices,
            List<PolygonMesh.Face> faces,
            long nextVertex,
            long nextFace) {
        GeometryData geometry() {
            if (sphere != null) {
                return sphere;
            }
            return closed
                    ? PolygonMesh.closedSolid(vertices, faces, nextVertex, nextFace)
                    : PolygonMesh.surface(vertices, faces, nextVertex, nextFace);
        }
    }

    public record Texture(int width, int height, List<Integer> pixels) {
        Texture2D texture() {
            return new Texture2D(
                    width, height, pixels.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    public record Surface(Material material, Texture top, Texture bottom, Texture sides) {
        Material restored() {
            return top == null
                    ? material
                    : material.withTextures(
                            new CubeTextures(top.texture(), bottom.texture(), sides.texture()));
        }
    }

    public record Instance(
            String name, int shape, int surface, Vec3 position, Vec3 rotation, Vec3 scale) {}

    static CaptureScene capture(List<SceneInstance> source) {
        var catalog = new Catalog();
        var instances = source.stream().map(catalog::capture).toList();
        return new CaptureScene(catalog.shapes, catalog.surfaces, instances);
    }

    /** Asset identity spans every animation frame in one capture. */
    static final class Catalog {
        private final java.util.IdentityHashMap<GeometryData, Integer> geometry =
                new java.util.IdentityHashMap<>();
        private final java.util.HashMap<Material, Integer> material = new java.util.HashMap<>();
        private final List<Shape> shapes = new ArrayList<>();
        private final List<Surface> surfaces = new ArrayList<>();

        CaptureScene assets() {
            return new CaptureScene(shapes, surfaces, List.of());
        }

        Instance capture(SceneInstance instance) {
            int shape =
                    geometry.computeIfAbsent(
                            instance.geometry(),
                            value -> {
                                shapes.add(shape(value));
                                return shapes.size() - 1;
                            });
            int surface =
                    material.computeIfAbsent(
                            instance.material(),
                            value -> {
                                surfaces.add(surface(value));
                                return surfaces.size() - 1;
                            });
            var transform = instance.transform();
            return new Instance(
                    instance.name(),
                    shape,
                    surface,
                    transform.position,
                    transform.rotation,
                    transform.scale);
        }
    }

    public List<SceneInstance> restore() {
        var geometry = shapes.stream().map(Shape::geometry).toList();
        var materials = surfaces.stream().map(Surface::restored).toList();
        return instances.stream()
                .map(
                        instance ->
                                new SceneInstance(
                                        instance.name(),
                                        geometry.get(instance.shape()),
                                        new Transform(
                                                instance.position(),
                                                instance.rotation(),
                                                instance.scale()),
                                        materials.get(instance.surface())))
                .toList();
    }

    private static Shape shape(GeometryData value) {
        if (value instanceof AnalyticSphere sphere) {
            return new Shape(sphere, true, List.of(), List.of(), 0, 0);
        }
        var mesh = (PolygonMesh) value;
        return new Shape(
                null,
                mesh.capabilities().closedBoundary(),
                mesh.editableVertices(),
                mesh.faces(),
                mesh.nextVertexId(),
                mesh.nextFaceId());
    }

    private static Surface surface(Material material) {
        var textures = material.textures();
        return textures == null
                ? new Surface(material, null, null, null)
                : new Surface(
                        material.withTextures(null),
                        texture(textures.top()),
                        texture(textures.bottom()),
                        texture(textures.sides()));
    }

    private static Texture texture(Texture2D texture) {
        var pixels = new ArrayList<Integer>();
        var rgb = new float[3];
        for (int y = 0; y < texture.height(); y++) {
            for (int x = 0; x < texture.width(); x++) {
                texture.sample((x + .5f) / texture.width(), (y + .5f) / texture.height(), rgb);
                pixels.add(
                        0xff000000 | encode(rgb[0]) << 16 | encode(rgb[1]) << 8 | encode(rgb[2]));
            }
        }
        return new Texture(texture.width(), texture.height(), pixels);
    }

    private static int encode(float linear) {
        double encoded =
                linear <= .0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        return (int) Math.round(encoded * 255);
    }
}
