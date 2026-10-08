package scenes.viewport;

import engine.*;
import engine.lights.PointLight;

import math.Vec3;

import misc.monads.Result;

import ui.console.Command;

import java.util.Arrays;

/** Console edits and render snapshot capture share the state monitor. */
public final class ViewportCommand implements Command {
    private final ViewportState state;
    private final int referenceWidth, referenceHeight;
    private String selected = "sphere";
    private Runnable clearCameraInput = () -> {};

    /** Headless callers use their initial sampling grid as the scale reference. */
    public ViewportCommand(ViewportState state) {
        this(state, state.sensorPixelsW(), state.sensorPixelsH());
    }

    public ViewportCommand(ViewportState state, int displayWidth, int displayHeight) {
        this.state = state;
        this.referenceWidth = displayWidth;
        this.referenceHeight = displayHeight;
    }

    ViewportCommand(ViewportState state, int width, int height, Runnable clearCameraInput) {
        this(state, width, height);
        this.clearCameraInput = clearCameraInput;
    }

    public static final String HELP = ViewportHelp.INDEX;

    @Override
    public Result<String, String> help(String... path) {
        return ViewportHelp.help(path);
    }

    @Override
    public Result<String, String> run(String... raw) {
        var args = Arrays.stream(raw).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (Arrays.equals(args, new String[] {"view", "camera", "focus", "center"}))
            return focusQuery(false, true);
        if (args.length == 5
                && args[1].equals("camera")
                && args[2].equals("focus")
                && args[3].equals("pull")
                && (args[4].equals("center") || args[4].equals("source")))
            return focusQuery(true, args[4].equals("center"));
        synchronized (state) {
            try {
                if (args.length < 2) return help();
                if (args[1].equals("help")) return help(Arrays.copyOfRange(args, 2, args.length));
                if (args[args.length - 1].equals("help") || args[args.length - 1].equals("--help"))
                    return help(Arrays.copyOfRange(args, 1, args.length - 1));
                String op = args[1];
                if (args.length == 2
                        && (op.equals("light")
                                || op.equals("mesh")
                                || op.equals("camera")
                                || op.equals("interactive"))) return help(op);
                switch (op) {
                    case "status" -> require(args, 2);
                    case "acceleration" -> {
                        require(args, 3);
                        if (!args[2].equals("bvh") && !args[2].equals("brute"))
                            throw new IllegalArgumentException("view acceleration bvh/brute");
                        state.acceleration(args[2].equals("bvh"));
                    }
                    case "mesh" -> {
                        require(args, 4);
                        if (!args[2].equals("detail"))
                            throw new IllegalArgumentException("view mesh detail <4..64>");
                        var geometry = state.instances().get(index(selected)).geometry();
                        if (!(geometry instanceof PolygonMesh))
                            throw new IllegalArgumentException("Select a procedural mesh instance");
                        var mesh =
                                PolygonMesh.approximateSphere(
                                        new AnalyticSphere(Vec3.ZERO, 1),
                                        Integer.parseInt(args[3]));
                        var next =
                                state.instances().stream()
                                        .map(
                                                o ->
                                                        o.geometry() == geometry
                                                                ? new SceneInstance(
                                                                        o.name(),
                                                                        mesh,
                                                                        o.transform(),
                                                                        o.material())
                                                                : o)
                                        .toList();
                        for (int i = 0; i < next.size(); i++) state.instances().set(i, next.get(i));
                    }
                    case "copy" -> {
                        require(args, 3);
                        if (!args[2].matches("[a-zA-Z0-9_-]+")
                                || state.instances().stream()
                                        .anyMatch(o -> o.name().equals(args[2])))
                            throw new IllegalArgumentException("Copy needs a unique object name");
                        if (state.instances().size() >= 128)
                            throw new IllegalArgumentException("Maximum 128 instances");
                        var o = state.instances().get(index(selected));
                        var t = o.transform();
                        state.instances()
                                .add(
                                        new SceneInstance(
                                                args[2],
                                                o.geometry(),
                                                new Transform(
                                                        t.position.add(new Vec3(0, 0, 3)),
                                                        t.rotation,
                                                        t.scale),
                                                o.material()));
                        selected = args[2];
                    }
                    case "remove" -> {
                        require(args, 3);
                        int i = index(args[2]);
                        if (state.instances().size() == 1)
                            throw new IllegalArgumentException("Keep at least one instance");
                        state.instances().remove(i);
                        if (selected.equals(args[2]))
                            selected = state.instances().getFirst().name();
                    }
                    case "preset" -> {
                        require(args, 3);
                        ScenePresets.load(state, args[2]);
                        selected = state.instances().getFirst().name();
                    }
                    case "reset" -> {
                        require(args, 2);
                        ScenePresets.load(state, state.preset());
                        selected = state.instances().getFirst().name();
                    }
                    case "camera" -> {
                        return camera(args);
                    }
                    case "exposure" -> {
                        require(args, 3);
                        state.exposure(number(args[2]));
                    }
                    case "resolution" -> resolution(args);
                    case "temporal" -> {
                        if (args.length == 2) return help("temporal");
                        if (args[2].equals("budget")) {
                            if (args.length == 3) return help("temporal", "budget");
                            switch (args[3]) {
                                case "on", "off" -> {
                                    require(args, 4);
                                    state.temporalBudget(args[3].equals("on"));
                                }
                                case "samples" -> {
                                    require(args, 5);
                                    state.motionSamples(Integer.parseInt(args[4]));
                                }
                                case "scale" -> {
                                    require(args, 5);
                                    state.motionScale(Double.parseDouble(args[4]));
                                }
                                default ->
                                        throw new IllegalArgumentException(
                                                "view temporal budget on/off/samples/scale");
                            }
                        } else {
                            require(args, 3);
                            if (!args[2].equals("on") && !args[2].equals("off"))
                                throw new IllegalArgumentException("view temporal on/off");
                            state.temporal(args[2].equals("on"));
                        }
                    }
                    case "interactive" -> {
                        switch (args[2]) {
                            case "on", "off" -> {
                                require(args, 3);
                                state.interactive(args[2].equals("on"));
                            }
                            case "target" -> {
                                require(args, 4);
                                state.interactiveMillis(Double.parseDouble(args[3]));
                            }
                            case "min" -> {
                                require(args, 5);
                                state.interactiveMinimum(
                                        Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                            }
                            default ->
                                    throw new IllegalArgumentException(
                                            "view interactive on/off/target/min; view interactive"
                                                    + " help");
                        }
                    }
                    case "depth" -> {
                        require(args, 3);
                        state.pathDepth(Integer.parseInt(args[2]));
                    }
                    case "samples" -> {
                        require(args, 3);
                        state.samplesPerFrame(Integer.parseInt(args[2]));
                    }
                    case "workers" -> {
                        require(args, 3);
                        state.workers(Integer.parseInt(args[2]));
                    }
                    case "tile" -> {
                        require(args, 3);
                        state.tileSize(Integer.parseInt(args[2]));
                    }
                    case "seed" -> {
                        require(args, 3);
                        state.seed(Long.parseLong(args[2]));
                    }
                    case "target" -> {
                        require(args, 3);
                        state.sampleTarget(Long.parseLong(args[2]));
                    }
                    case "restart" -> {
                        require(args, 2);
                        state.restart();
                    }
                    case "pause", "resume" -> {
                        require(args, 2);
                        state.paused(op.equals("pause"));
                    }
                    case "select" -> {
                        require(args, 3);
                        index(args[2]);
                        selected = args[2];
                    }
                    case "color" -> {
                        require(args, 3);
                        int index = index(selected);
                        var object = state.instances().get(index);
                        var material =
                                object.material()
                                        .withColor(
                                                Material.srgb(
                                                                object.material().name(),
                                                                rgb(args[2]))
                                                        .color());
                        // Shared material edits affect every referencing object.
                        editMaterial(material);
                    }
                    case "type" -> {
                        require(args, 3);
                        var object = state.instances().get(index(selected));
                        var material =
                                object.material()
                                        .withKind(
                                                Material.Kind.valueOf(
                                                        args[2].toUpperCase(
                                                                java.util.Locale.ROOT)));
                        editMaterial(material);
                    }
                    case "ior" -> {
                        require(args, 3);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withIor(number(args[2])));
                    }
                    case "absorption" -> {
                        require(args, 5);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withAbsorption(vector(args, 2)));
                    }
                    case "scattering" -> {
                        require(args, 3);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withScattering(number(args[2])));
                    }
                    case "anisotropy" -> {
                        require(args, 3);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withAnisotropy(number(args[2])));
                    }
                    case "roughness" -> {
                        require(args, 3);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withRoughness(number(args[2])));
                    }
                    case "emission" -> {
                        require(args, 5);
                        editMaterial(
                                state.instances()
                                        .get(index(selected))
                                        .material()
                                        .withEmission(vector(args, 2)));
                    }
                    case "material" -> {
                        require(args, 3);
                        var material =
                                state.instances().stream()
                                        .map(SceneInstance::material)
                                        .filter(m -> m.name().equals(args[2]))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "Unknown material: " + args[2]));
                        int index = index(selected);
                        state.instances()
                                .set(index, state.instances().get(index).withMaterial(material));
                    }
                    case "move", "rotate", "scale" -> {
                        require(args, 5);
                        int index = index(selected);
                        var object = state.instances().get(index);
                        var t = object.transform();
                        var v = vector(args, 2);
                        var next =
                                new Transform(
                                        op.equals("move") ? v : t.position,
                                        op.equals("rotate") ? v : t.rotation,
                                        op.equals("scale") ? v : t.scale);
                        state.instances().set(index, object.withTransform(next));
                    }
                    case "light" -> {
                        if (args.length < 3)
                            throw new IllegalArgumentException(
                                    "view light position/color/intensity ...");
                        if (state.instances().stream()
                                .anyMatch(o -> o.name().equals("area-light"))) {
                            editAreaLight(args);
                            break;
                        }
                        if (state.lights().isEmpty())
                            throw new IllegalArgumentException(
                                    "No point light; select a rectangular object and edit"
                                            + " emission");
                        var light = (PointLight) state.lights().getFirst();
                        PointLight next =
                                switch (args[2]) {
                                    case "position" -> {
                                        require(args, 6);
                                        yield new PointLight(
                                                vector(args, 3), light.color(), light.intensity());
                                    }
                                    case "color" -> {
                                        require(args, 4);
                                        yield new PointLight(
                                                light.position(),
                                                Material.srgb("light", rgb(args[3])).color(),
                                                light.intensity());
                                    }
                                    case "intensity" -> {
                                        require(args, 4);
                                        yield new PointLight(
                                                light.position(), light.color(), number(args[3]));
                                    }
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "view light position/color/intensity ...");
                                };
                        state.lights().set(0, next);
                    }
                    default ->
                            throw new IllegalArgumentException("Unknown view command; view help");
                }
                return Result.success(op.equals("status") ? status() : confirmation(args));
            } catch (IllegalArgumentException e) {
                return Result.failure(e.getMessage());
            }
        }
    }

    private String confirmation(String[] args) {
        return switch (args[1]) {
            case "interactive" -> state.interactiveStatus();
            case "temporal" ->
                    "temporal="
                            + (state.temporal() ? "on" : "off")
                            + "; "
                            + state.temporalBudgetStatus();
            case "resolution" ->
                    "Updated resolution to "
                            + state.sensorPixelsW()
                            + "x"
                            + state.sensorPixelsH()
                            + ".";
            case "preset" -> "Loaded preset " + state.preset() + ".";
            case "reset" -> "Reset preset " + state.preset() + ".";
            case "restart" -> "Restarted sampling.";
            case "pause" -> "Paused sampling.";
            case "resume" -> "Resumed sampling.";
            case "select" -> "Selected " + selected + ".";
            case "copy" -> "Created and selected " + selected + ".";
            case "remove" -> "Removed " + args[2] + ".";
            case "light", "mesh" ->
                    "Updated "
                            + args[1]
                            + " "
                            + args[2]
                            + " to "
                            + String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                            + ".";
            default ->
                    "Updated "
                            + args[1]
                            + " to "
                            + String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                            + ".";
        };
    }

    private void resolution(String[] args) {
        if (args.length == 4) {
            state.resolution(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            return;
        }
        require(args, 3);
        String value = args[2];
        if (!value.endsWith("x")
                && !value.equals("native")
                && !value.equals("half")
                && !value.equals("quarter")) {
            state.resolution(Integer.parseInt(value));
            return;
        }
        double factor =
                switch (value) {
                    case "native" -> 1;
                    case "half" -> .5;
                    case "quarter" -> .25;
                    default -> Double.parseDouble(value.substring(0, value.length() - 1));
                };
        if (!Double.isFinite(factor) || factor <= 0)
            throw new IllegalArgumentException("Resolution multiplier must be positive and finite");
        long width = Math.round(referenceWidth * factor),
                height = Math.round(referenceHeight * factor);
        if (width < 64 || width > 1600 || height < 64 || height > 1600)
            throw new IllegalArgumentException("Scaled width and height must each be 64..1600");
        state.resolution((int) width, (int) height);
    }

    private String status() {
        var names = state.instances().stream().map(SceneInstance::name).toList();
        var materials =
                state.instances().stream().map(o -> o.material().name()).distinct().toList();
        String object =
                state.instances().stream()
                        .filter(o -> o.name().equals(selected))
                        .findFirst()
                        .map(
                                o ->
                                        o.name()
                                                + " "
                                                + o.transform()
                                                + " material="
                                                + o.material().name()
                                                + " type="
                                                + o.material().kind()
                                                + " linear RGB="
                                                + o.material().color()
                                                + " IOR="
                                                + o.material().ior()
                                                + " absorption="
                                                + o.material().absorption()
                                                + " scattering="
                                                + o.material().scattering()
                                                + " anisotropy="
                                                + o.material().anisotropy()
                                                + " roughness="
                                                + o.material().roughness()
                                                + " emission="
                                                + o.material().emission())
                        .orElse("none");
        return "Preset="
                + state.preset()
                + " exposure="
                + state.exposure()
                + " stops; sensor="
                + state.sensorPixelsW()
                + "x"
                + state.sensorPixelsH()
                + "; acceleration="
                + (state.acceleration() ? "bvh" : "brute")
                + "; primitives="
                + state.instances().stream()
                        .mapToInt(
                                o ->
                                        o.geometry() instanceof PolygonMesh mesh
                                                ? mesh.renderPrimitiveCount()
                                                : 1)
                        .sum()
                + "; workers="
                + state.workers()
                + "; tile="
                + state.tileSize()
                + "; "
                + state.interactiveStatus()
                + "; "
                + state.camera().summary()
                + "; "
                + state.temporalStatus()
                + "; "
                + state.focusStatus()
                + "; "
                + state.temporalBudgetStatus()
                + "; depth="
                + state.pathDepth()
                + "; spp/batch max="
                + state.samplesPerFrame()
                + "; accumulated="
                + state.accumulatedSamples()
                + "; "
                + state.samplingStatus()
                + "; target="
                + state.sampleTarget()
                + "; seed="
                + state.seed()
                + "\nObjects="
                + names
                + " materials="
                + materials
                + "\nSelected: "
                + object
                + "\nPoint lights: "
                + state.lights()
                + "\nEmitters: "
                + state.instances().stream()
                        .filter(o -> o.material().emissive())
                        .map(
                                o ->
                                        o.name()
                                                + " "
                                                + o.transform()
                                                + " radiance="
                                                + o.material().emission())
                        .toList()
                + "\nview help for controls";
    }

    private Result<String, String> camera(String[] args) {
        var current = state.camera();
        switch (args[2]) {
            case "status" -> {
                require(args, 3);
                return Result.success(
                        current.summary()
                                + " eye="
                                + current.eye()
                                + " forward="
                                + current.forward()
                                + "; "
                                + state.temporalStatus()
                                + "; "
                                + state.focusStatus());
            }
            case "reset" -> {
                require(args, 3);
                clearCameraInput.run();
                ScenePresets.resetCamera(state);
                return Result.success("Reset camera.");
            }
            case "mode" -> {
                require(args, 4);
                var next = current.withMode(args[3]);
                state.camera(next);
                if (current.projection() != next.projection())
                    state.focusController().framingChanged();
                return Result.success(
                        next.summary()
                                + (next.mode() == Camera.Mode.ORTHOGRAPHIC && current.height() == 0
                                        ? "; matched at focus distance "
                                                + current.focus()
                                                + " units"
                                        : ""));
            }
            case "projection" -> {
                require(args, 4);
                var next = current.withProjection(args[3]);
                state.camera(next);
                if (current.projection() != next.projection())
                    state.focusController().framingChanged();
            }
            case "fov" -> {
                require(args, 4);
                var next = current.withFov(number(args[3]));
                state.camera(next);
                if (!next.equals(current)) state.focusController().framingChanged();
            }
            case "height" -> {
                require(args, 4);
                var next = current.withHeight(number(args[3]));
                state.camera(next);
                if (!next.equals(current)) state.focusController().framingChanged();
            }
            case "aperture" -> {
                require(args, 4);
                state.camera(current.withAperture(number(args[3])));
            }
            case "focus" -> {
                if (args.length == 3) return help("camera", "focus");
                var focus = state.focusController();
                switch (args[3]) {
                    case "distance" -> {
                        require(args, 5);
                        focus.manual(number(args[4]), false);
                    }
                    case "mode" -> {
                        require(args, 5);
                        focus.mode(args[4]);
                    }
                    case "source" -> {
                        require(args, 7);
                        if (!args[4].equals("screen"))
                            throw new IllegalArgumentException(
                                    "Focus source must be screen <u> <v>");
                        focus.source(
                                new FocusTargetSource.Screen(number(args[5]), number(args[6])));
                    }
                    case "pull" -> {
                        require(args, 6);
                        if (!args[4].equals("distance"))
                            throw new IllegalArgumentException(
                                    "Focus pull distance <units> | center | source");
                        focus.manual(number(args[5]), true);
                    }
                    case "transition" -> {
                        require(args, 6);
                        if (args[4].equals("duration")) focus.duration(Double.parseDouble(args[5]));
                        else if (args[4].equals("curve")) focus.curve(args[5]);
                        else
                            throw new IllegalArgumentException(
                                    "Focus transition duration <ms> | curve linear/smooth");
                    }
                    case "auto" -> {
                        require(args, 6);
                        if (args[4].equals("delay")) focus.delay(Double.parseDouble(args[5]));
                        else if (args[4].equals("tolerance"))
                            focus.tolerance(Double.parseDouble(args[5]));
                        else
                            throw new IllegalArgumentException(
                                    "Focus auto delay <ms> | tolerance <percent>");
                    }
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown focus control; view camera focus help");
                }
                return Result.success(state.focusStatus());
            }
            default ->
                    throw new IllegalArgumentException("Unknown camera control; view camera help");
        }
        return Result.success(state.camera().summary());
    }

    private Result<String, String> focusQuery(boolean pull, boolean center) {
        ViewportState snapshot;
        FocusTargetSource source;
        long epoch;
        synchronized (state) {
            var focus = state.focusController();
            source = center ? FocusTargetSource.CENTER : focus.source();
            if (focus.request(source, pull))
                return Result.success("Focus query queued; view camera status shows result.");
            snapshot = state.renderSnapshot();
            epoch = focus.epoch();
        }
        var measured =
                new CameraFocus()
                        .resolve(
                                source,
                                snapshot.camera(),
                                CameraFocus.Scene.capture(snapshot),
                                epoch,
                                0,
                                System::nanoTime);
        if (measured.error() != null) return Result.failure(measured.error());
        synchronized (state) {
            if (epoch != state.focusController().epoch())
                return Result.failure("Focus selection changed; retry");
            return publishFocus(snapshot, measured.distance(), pull);
        }
    }

    public Result<String, String> publishFocus(ViewportState captured, float distance) {
        return publishFocus(captured, distance, false);
    }

    private Result<String, String> publishFocus(
            ViewportState captured, float distance, boolean pull) {
        synchronized (state) {
            if (!captured.camera().equals(state.camera())
                    || !captured.renderKey().sameTransport(state.renderKey()))
                return Result.failure(
                        "View changed during center focus; retry view camera focus center");
            state.focusController().manual(distance, pull);
            return Result.success("Focus distance=" + distance + " units (first surface)");
        }
    }

    private void editAreaLight(String[] args) {
        int index = index("area-light");
        var object = state.instances().get(index);
        var t = object.transform();
        var m = object.material();
        switch (args[2]) {
            case "position" -> {
                require(args, 6);
                object = object.withTransform(new Transform(vector(args, 3), t.rotation, t.scale));
            }
            case "size" -> {
                require(args, 5);
                object =
                        object.withTransform(
                                new Transform(
                                        t.position,
                                        t.rotation,
                                        new Vec3(number(args[3]), t.scale.y(), number(args[4]))));
            }
            case "color", "intensity" -> {
                require(args, 4);
                float peak =
                        Math.max(m.emission().x(), Math.max(m.emission().y(), m.emission().z()));
                Vec3 color = peak > 0 ? m.emission().scale(1 / peak) : new Vec3(1, 1, 1);
                if (args[2].equals("color")) color = Material.srgb("light", rgb(args[3])).color();
                else peak = number(args[3]);
                editMaterial(m.withEmission(color.scale(peak)));
                return;
            }
            default ->
                    throw new IllegalArgumentException(
                            "view light position/color/intensity/size ...");
        }
        state.instances().set(index, object);
    }

    private void editMaterial(Material material) {
        // Validate every affected instance before publishing any shared edit.
        var next =
                state.instances().stream()
                        .map(
                                o ->
                                        o.material().name().equals(material.name())
                                                ? o.withMaterial(material)
                                                : o)
                        .toList();
        for (int i = 0; i < next.size(); i++) state.instances().set(i, next.get(i));
    }

    private int index(String name) {
        for (int i = 0; i < state.instances().size(); i++)
            if (state.instances().get(i).name().equals(name)) return i;
        throw new IllegalArgumentException("Unknown object: " + name);
    }

    private static void require(String[] args, int length) {
        if (args.length != length) throw new IllegalArgumentException(ViewportHelp.usage(args));
    }

    private static float number(String s) {
        float n = Float.parseFloat(s);
        if (!Float.isFinite(n)) throw new IllegalArgumentException("Value must be finite");
        return n;
    }

    private static Vec3 vector(String[] args, int start) {
        return new Vec3(number(args[start]), number(args[start + 1]), number(args[start + 2]));
    }

    private static int rgb(String s) {
        if (!s.matches("#?[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Color needs six hex digits");
        return Integer.parseInt(s.replace("#", ""), 16);
    }
}
