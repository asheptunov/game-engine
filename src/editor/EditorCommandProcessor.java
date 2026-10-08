package editor;

import engine.*;
import math.Vec3;

import java.nio.file.Path;
import java.util.*;

/** Text commands share the controller operations used by Swing actions. */
public final class EditorCommandProcessor {
    private final EditorController controller;
    public EditorCommandProcessor(EditorController controller) { this.controller = Objects.requireNonNull(controller); }

    public String execute(String command) {
        try {
            var words = command == null ? List.<String>of() : Arrays.stream(command.trim().split("\\s+"))
                    .filter(s -> !s.isBlank()).toList();
            if (words.isEmpty()) return help();
            return switch (words.getFirst().toLowerCase(Locale.ROOT)) {
                case "help" -> help();
                case "create" -> create(words);
                case "select" -> select(words);
                case "rename" -> rename(words);
                case "transform" -> transform(words);
                case "duplicate" -> { exact(words, 1, "duplicate"); yield done(controller.duplicateSelection()); }
                case "delete" -> { exact(words, 1, "delete"); yield done(controller.deleteSelection()); }
                case "reparent" -> reparent(words);
                case "material" -> material(words);
                case "light" -> light(words);
                case "camera" -> camera(words);
                case "mode" -> mode(words);
                case "sphere" -> sphere(words);
                case "mesh" -> mesh(words);
                case "vertex" -> vertex(words);
                case "edge" -> edge(words);
                case "face" -> face(words);
                case "undo" -> { exact(words, 1, "undo"); yield done(controller.undo()); }
                case "redo" -> { exact(words, 1, "redo"); yield done(controller.redo()); }
                case "save" -> save(words);
                case "load" -> load(words);
                case "status" -> { exact(words, 1, "status"); yield describe(); }
                default -> "Unknown command. " + help();
            };
        } catch (RuntimeException error) {
            return "Error: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private String create(List<String> words) {
        require(words, 2, "create box|sphere|plane|group|light|camera");
        exact(words, 2, "create box|sphere|plane|group|light|camera");
        var primitive = switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "box" -> EditorController.Primitive.BOX;
            case "sphere" -> EditorController.Primitive.SPHERE;
            case "plane" -> EditorController.Primitive.PLANE;
            case "group" -> EditorController.Primitive.GROUP;
            case "light" -> EditorController.Primitive.POINT_LIGHT;
            case "camera" -> EditorController.Primitive.CAMERA;
            default -> throw new IllegalArgumentException("Unknown primitive: " + words.get(1));
        };
        return done(controller.create(primitive));
    }

    private String select(List<String> words) {
        require(words, 2, "select <node-id|label>");
        var query = String.join(" ", words.subList(1, words.size())); var snapshot = controller.snapshot(); NodeId id;
        try { id = NodeId.parse(query); }
        catch (IllegalArgumentException ignored) {
            var matches = snapshot.nodes().stream().filter(n -> n.label().equalsIgnoreCase(query)).toList();
            if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "No node named " + query : "Label is ambiguous; use the node UUID");
            id = matches.getFirst().id();
        }
        return done(controller.select(id));
    }

    private String rename(List<String> words) { require(words, 2, "rename <new label>"); return done(controller.rename(selected(), String.join(" ", words.subList(1, words.size())))); }
    private String transform(List<String> words) {
        exact(words, 10, "transform px py pz rx ry rz sx sy sz"); float[] v = new float[9];
        for (int i = 0; i < v.length; i++) v[i] = Float.parseFloat(words.get(i + 1));
        return done(controller.applyTransform(selected(), new Transform(new Vec3(v[0], v[1], v[2]), new Vec3(v[3], v[4], v[5]), new Vec3(v[6], v[7], v[8]))));
    }
    private String reparent(List<String> words) { exact(words, 2, "reparent root|<parent-id>"); return done(controller.reparent(selected(), words.get(1).equalsIgnoreCase("root") ? null : NodeId.parse(words.get(1)))); }
    private String material(List<String> words) {
        require(words, 2, "material edit diffuse|mirror|dielectric red green blue roughness ior");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "edit" -> {
                exact(words, 8, "material edit diffuse|mirror|dielectric red green blue roughness ior");
                var node = controller.snapshot().requireNode(selected()); if (node.geometry() == null) throw new IllegalArgumentException("Selected node has no material");
                var asset = controller.snapshot().requireMaterial(node.geometry().materialId());
                var value = asset.material().withKind(Material.Kind.valueOf(words.get(2).toUpperCase(Locale.ROOT)))
                        .withColor(new Vec3(Float.parseFloat(words.get(3)), Float.parseFloat(words.get(4)), Float.parseFloat(words.get(5))))
                        .withRoughness(Float.parseFloat(words.get(6))).withIor(Float.parseFloat(words.get(7)));
                yield done(controller.applyMaterial(node.id(), value));
            }
            default -> throw new IllegalArgumentException("Use material edit");
        };
    }
    private String light(List<String> words) {
        require(words, 2, "light set red green blue intensity | remove");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "remove" -> { exact(words, 2, "light remove"); yield done(controller.setPointLight(selected(), null)); }
            case "set" -> { exact(words, 6, "light set red green blue intensity"); yield done(controller.setPointLight(selected(), new PointLightComponent(
                    new Vec3(Float.parseFloat(words.get(2)), Float.parseFloat(words.get(3)), Float.parseFloat(words.get(4))), Float.parseFloat(words.get(5))))); }
            default -> throw new IllegalArgumentException("Use light set or light remove");
        };
    }
    private String camera(List<String> words) {
        require(words, 2, "camera set perspective|orthographic framing focus aperture | remove");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "remove" -> { exact(words, 2, "camera remove"); yield done(controller.setCamera(selected(), null)); }
            case "set" -> {
                exact(words, 6, "camera set perspective|orthographic framing focus aperture");
                var node = controller.snapshot().requireNode(selected()); var camera = node.camera() == null ? StarterScene.canonicalCamera() : node.camera().camera();
                var projection = words.get(2).toLowerCase(Locale.ROOT); camera = camera.withProjection(projection);
                camera = projection.equals("orthographic") ? camera.withHeight(Float.parseFloat(words.get(3))) : camera.withFov(Float.parseFloat(words.get(3)));
                camera = camera.withFocus(Float.parseFloat(words.get(4))).withAperture(Float.parseFloat(words.get(5)));
                yield done(controller.setCamera(node.id(), new CameraComponent(camera)));
            }
            default -> throw new IllegalArgumentException("Use camera set or camera remove");
        };
    }
    private String mode(List<String> words){
        exact(words, 2, "mode object|vertex|edge|face");
        var mode = switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "object" -> EditorController.SelectionMode.OBJECT;
            case "vertex" -> EditorController.SelectionMode.VERTEX;
            case "edge" -> EditorController.SelectionMode.EDGE;
            case "face" -> EditorController.SelectionMode.FACE;
            default -> throw new IllegalArgumentException("Use mode object, vertex, edge, or face");
        };
        return done(controller.setSelectionMode(mode));
    }
    private String vertex(List<String> words) {
        require(words, 2, "vertex select <id> | vertex move <local-dx> <local-dy> <local-dz>");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "select" -> {
                exact(words, 3, "vertex select <id>");
                yield done(controller.selectVertex(Long.parseLong(words.get(2))));
            }
            case "move" -> {
                exact(words, 5, "vertex move <local-dx> <local-dy> <local-dz>");
                yield done(controller.translateSelectedElement(vector(words, 2)));
            }
            default -> throw new IllegalArgumentException("Use vertex select or vertex move");
        };
    }
    private String edge(List<String> words) {
        require(words, 2, "edge select <first-id> <second-id> | edge move <local-dx> <local-dy> <local-dz>");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "select" -> {
                exact(words, 4, "edge select <first-id> <second-id>");
                yield done(controller.selectEdge(
                        Long.parseLong(words.get(2)), Long.parseLong(words.get(3))));
            }
            case "move" -> {
                exact(words, 5, "edge move <local-dx> <local-dy> <local-dz>");
                yield done(controller.translateSelectedElement(vector(words, 2)));
            }
            default -> throw new IllegalArgumentException("Use edge select or edge move");
        };
    }
    private String sphere(List<String> words) {
        exact(words, 6, "sphere set <center-x> <center-y> <center-z> <radius>");
        if (!words.get(1).equalsIgnoreCase("set")) {
            throw new IllegalArgumentException("Use sphere set");
        }
        return done(controller.applyAnalyticSphere(
                selected(), vector(words, 2), Float.parseFloat(words.get(5))));
    }
    private String mesh(List<String> words) {
        require(words, 2, "mesh approximate <detail-4..64>");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "approximate" -> {
                exact(words, 3, "mesh approximate <detail-4..64>");
                yield done(controller.approximateAnalyticSphere(
                        selected(), Integer.parseInt(words.get(2))));
            }
            default -> throw new IllegalArgumentException("Use mesh approximate");
        };
    }
    private String face(List<String> words) {
        require(words, 2,
                "face select <face-id> | face move <local-dx> <local-dy> <local-dz> | face extrude <positive-distance>");
        return switch (words.get(1).toLowerCase(Locale.ROOT)) {
            case "select" -> {
                exact(words, 3, "face select <face-id>");
                yield done(controller.selectFace(Long.parseLong(words.get(2))));
            }
            case "move" -> {
                exact(words, 5, "face move <local-dx> <local-dy> <local-dz>");
                yield done(controller.translateSelectedElement(vector(words, 2)));
            }
            case "extrude" -> {
                exact(words, 3, "face extrude <positive-distance>");
                yield done(controller.extrudeSelectedFace(Float.parseFloat(words.get(2))));
            }
            default -> throw new IllegalArgumentException("Use face select, face move, or face extrude");
        };
    }
    private String save(List<String> words) {
        if (controller.state().busy()) return "Error: file work is already in progress";
        Path path = words.size() > 1 ? Path.of(String.join(" ", words.subList(1, words.size()))) : controller.state().file();
        if (path == null) throw new IllegalArgumentException("save needs a .scene.xml path"); controller.save(path); return "Save started: " + path;
    }
    private String load(List<String> words) {
        require(words, 2, "load <path.scene.xml>");
        if (controller.state().busy()) return "Error: file work is already in progress";
        if (controller.dirty()) return "Error: save or discard unsaved changes before loading";
        var path = Path.of(String.join(" ", words.subList(1, words.size()))); controller.load(path); return "Load started: " + path;
    }
    private String describe() {
        var state = controller.state();
        return "revision=" + state.snapshot().revision()
                + " nodes=" + state.snapshot().nodes().size()
                + " selected=" + state.selection()
                + " mode=" + state.selectionMode().name().toLowerCase(Locale.ROOT)
                + " element=" + element(state)
                + " dirty=" + state.dirty();
    }
    private static String element(EditorController.State state) {
        if (state.vertexSelection() != null) return "vertex:" + state.vertexSelection().vertexId();
        if (state.edgeSelection() != null) {
            return "edge:" + state.edgeSelection().firstVertexId() + "-" + state.edgeSelection().secondVertexId();
        }
        if (state.faceSelection() != null) return "face:" + state.faceSelection().faceId();
        return "none";
    }
    private static Vec3 vector(List<String> words, int offset) {
        return new Vec3(Float.parseFloat(words.get(offset)), Float.parseFloat(words.get(offset + 1)),
                Float.parseFloat(words.get(offset + 2)));
    }
    private NodeId selected() { var id = controller.selection(); if (id == null) throw new IllegalArgumentException("Select a node first"); return id; }
    private String done(boolean ok) { return ok ? controller.state().status() : "Error: " + controller.state().status(); }
    private static void require(List<String> words, int size, String usage) { if (words.size() < size) throw new IllegalArgumentException("Usage: " + usage); }
    private static void exact(List<String> words, int size, String usage) { if (words.size() != size) throw new IllegalArgumentException("Usage: " + usage); }
    private static String help() { return "Commands: create, select, rename, transform, duplicate, delete, reparent, material edit <kind> <red green blue> <roughness> <ior>, light, camera, mode object|vertex|edge|face, sphere set <center x y z> <radius>, mesh approximate <detail 4..64>, vertex select <id>, vertex move <local dx dy dz>, edge select <a> <b>, edge move <local dx dy dz>, face select <id>, face move <local dx dy dz>, face extrude <distance>, undo, redo, save, load, status"; }
}
