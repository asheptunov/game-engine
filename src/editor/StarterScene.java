package editor;

import engine.*;
import engine.objects.Rect;
import math.Vec3;

/** Small responsive scene used by a fresh editor document. */
public final class StarterScene {
    private StarterScene() {}

    public static SceneDocument create() {
        var document = new SceneDocument();
        populate(document);
        return document;
    }

    public static void populate(SceneDocument document) {
        document.transact(edit -> {
            var floorMaterial = edit.createMaterial("Warm gray", Material.srgb("warm-gray", 0xb8afa4));
            var clayMaterial = edit.createMaterial("Terracotta", Material.srgb("terracotta", 0xd66f43));
            var tealMaterial = edit.createMaterial("Teal", Material.srgb("teal", 0x319795));
            var darkMaterial = edit.createMaterial("Charcoal", Material.srgb("charcoal", 0x26313a));
            var floorGeometry = edit.createGeometry("Floor plane", PolygonMesh.parallelogram(
                    new Vec3(-7, -1, -3), new Vec3(0, 0, 14), new Vec3(14, 0, 0)));
            var pedestalGeometry = edit.createGeometry("Pedestal box", BoxGeometry.UNIT);
            var tealBoxGeometry = edit.createGeometry("Teal box geometry", BoxGeometry.UNIT);
            var sphereGeometry = edit.createGeometry("Unit sphere", new AnalyticSphere(Vec3.ZERO, 1));
            var floor = edit.createNode("Floor", null, Transform.IDENTITY);
            edit.assignGeometry(floor, floorGeometry, floorMaterial);
            var composition = edit.createNode("Composition", null, Transform.IDENTITY);
            var pedestal = edit.createNode("Pedestal", composition,
                    transform(0, -.55f, 3.3f, 0, -12, 0, 1.9f, .45f, 1.7f));
            edit.assignGeometry(pedestal, pedestalGeometry, darkMaterial);
            var sphere = edit.createNode("Terracotta sphere", composition,
                    transform(-1.05f, .25f, 2.8f, 0, 0, 0, 1, 1, 1));
            edit.assignGeometry(sphere, sphereGeometry, clayMaterial);
            var box = edit.createNode("Teal box", composition,
                    transform(1.15f, .05f, 3.65f, 0, 28, 8, .72f, 1.05f, .72f));
            edit.assignGeometry(box, tealBoxGeometry, tealMaterial);
            var key = edit.createNode("Key light", null,
                    transform(-4.2f, 5.5f, -1.2f, 0, 0, 0, 1, 1, 1));
            edit.setPointLight(key, new PointLightComponent(new Vec3(1, .78f, .58f), 145));
            var fill = edit.createNode("Fill light", null,
                    transform(4.5f, 3.2f, .5f, 0, 0, 0, 1, 1, 1));
            edit.setPointLight(fill, new PointLightComponent(new Vec3(.32f, .55f, 1), 65));
            addCamera(edit, "Camera A", new Vec3(7.2f, 4.3f, -7.5f), new Vec3(0, 0, 3.2f));
            addCamera(edit, "Camera B", new Vec3(-6.4f, 5.8f, -2.2f), new Vec3(0, 0, 3.4f));
        });
    }

    private static void addCamera(SceneEdit edit, String label, Vec3 eye, Vec3 target) {
        var forward = target.sub(eye).normalized();
        float pitch = (float) Math.toDegrees(-Math.asin(forward.y()));
        float yaw = (float) Math.toDegrees(Math.atan2(forward.x(), forward.z()));
        var id = edit.createNode(label, null,
                transform(eye.x(), eye.y(), eye.z(), pitch, yaw, 0, 1, 1, 1));
        edit.setCamera(id, new CameraComponent(canonicalCamera()));
    }

    public static Camera canonicalCamera() {
        float height = (float) (2 * Math.tan(Math.toRadians(50) / 2));
        float width = height * 1.6f;
        return new Camera(Vec3.ZERO, new Rect(new Vec3(-width / 2, -height / 2, 1),
                new Vec3(width, 0, 0), new Vec3(0, height, 0)));
    }

    private static Transform transform(float px, float py, float pz, float rx, float ry, float rz,
                                       float sx, float sy, float sz) {
        return new Transform(new Vec3(px, py, pz), new Vec3(rx, ry, rz), new Vec3(sx, sy, sz));
    }
}
