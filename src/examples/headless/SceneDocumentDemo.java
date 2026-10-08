package examples.headless;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

import javax.imageio.ImageIO;

/** Independent document/query/persistence consumer using only public engine APIs. */
public final class SceneDocumentDemo {
    private SceneDocumentDemo() {}

    public static void main(String[] args) throws Exception {
        Path scene = Path.of(args.length > 0 ? args[0] : "out/engine/document-demo.scene.xml");
        Path imagePath = Path.of(args.length > 1 ? args[1] : "out/engine/document-demo.png");
        var document = new SceneDocument();
        var cameraId = new NodeId[1];
        document.transact(
                edit -> {
                    var open =
                            edit.createGeometry(
                                    "open backdrop",
                                    PolygonMesh.triangleSurface(
                                            List.of(
                                                    new Vec3(-4, -3, 0),
                                                    new Vec3(0, 4, 0),
                                                    new Vec3(4, -3, 0)),
                                            new int[] {0, 1, 2},
                                            new long[] {410}));
                    var closed =
                            edit.createGeometry(
                                    "closed tetrahedron",
                                    PolygonMesh.triangleClosedSolid(
                                            List.of(
                                                    Vec3.ZERO,
                                                    new Vec3(1, 0, 0),
                                                    new Vec3(0, 1, 0),
                                                    new Vec3(0, 0, 1)),
                                            new int[] {0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3},
                                            new long[] {500, 501, 502, 503}));
                    var wall = edit.createMaterial("wall", Material.srgb("ignored", 0x8090a8));
                    var glass =
                            edit.createMaterial(
                                    "glass",
                                    new Material(
                                            "ignored",
                                            new Vec3(1, 1, 1),
                                            Material.Kind.DIELECTRIC));
                    var backdrop =
                            edit.createNode(
                                    "backdrop",
                                    null,
                                    new Transform(new Vec3(0, 0, 7), Vec3.ZERO, new Vec3(1, 1, 1)));
                    edit.assignGeometry(backdrop, open, wall);
                    var solid =
                            edit.createNode(
                                    "glass solid",
                                    null,
                                    new Transform(
                                            new Vec3(-.3f, -.3f, 3),
                                            Vec3.ZERO,
                                            new Vec3(1.5f, 1.5f, 1.5f)));
                    edit.assignGeometry(solid, closed, glass);
                    var light =
                            edit.createNode(
                                    "light",
                                    null,
                                    new Transform(
                                            new Vec3(-2, 3, 0), Vec3.ZERO, new Vec3(1, 1, 1)));
                    edit.setPointLight(light, new PointLightComponent(new Vec3(1, .95f, .85f), 70));
                    var camera =
                            new Camera(
                                    Vec3.ZERO,
                                    new Rect(
                                            new Vec3(-.8f, -.5f, 1),
                                            new Vec3(1.6f, 0, 0),
                                            new Vec3(0, 1, 0)));
                    cameraId[0] = edit.createNode("camera", null, Transform.IDENTITY);
                    edit.setCamera(cameraId[0], new CameraComponent(camera));
                });
        Files.createDirectories(scene.toAbsolutePath().getParent());
        Files.createDirectories(imagePath.toAbsolutePath().getParent());
        SceneFiles.save(scene, document.snapshot());
        var loaded = SceneFiles.load(scene);
        var hit =
                SpatialQuery.prepare(loaded)
                        .pick(loaded.camera(cameraId[0]), .5f, .5f)
                        .orElseThrow();
        var settings =
                RenderSettings.defaults()
                        .withWorkers(1)
                        .withPathDepth(4)
                        .withSeed(812)
                        .withSamplesPerBatch(2)
                        .withSampleTarget(4);
        try (var session =
                        RenderEngine.openSession(
                                loaded.toWorldSnapshot(),
                                loaded.renderView(cameraId[0], 160, 100),
                                settings);
                var image = await(session, 4)) {
            write(image.rawPixels(), imagePath);
        }
        System.out.println(
                "Saved "
                        + scene
                        + ", rendered "
                        + imagePath
                        + ", picked node="
                        + hit.nodeId()
                        + " face="
                        + hit.sourceFaceId());
    }

    private static RenderImage await(RenderSession session, long samples) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            var image = session.acquireImage();
            if (image != null) {
                if (image.generation() == session.progress(image).requestedGeneration()
                        && image.samples() >= samples) return image;
                image.close();
            }
            Thread.sleep(1);
        }
        throw new IllegalStateException("Timed out waiting for render");
    }

    private static void write(RgbPixels rgb, Path output) throws Exception {
        var image = new BufferedImage(rgb.width(), rgb.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rgb.height(); y++)
            for (int x = 0; x < rgb.width(); x++) {
                int r = Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(0, x, y), 1)),
                        g = Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(1, x, y), 1)),
                        b = Byte.toUnsignedInt(DisplayMapping.encode(rgb.value(2, x, y), 1));
                image.setRGB(x, rgb.height() - 1 - y, (r << 16) | (g << 8) | b);
            }
        if (!ImageIO.write(image, "png", output.toFile()))
            throw new IllegalStateException("PNG writer unavailable");
    }
}
