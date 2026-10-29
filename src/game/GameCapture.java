package game;

import engine.Atmosphere;
import engine.FogRegion;
import engine.RenderSettings;
import engine.RenderView;
import engine.SceneInstance;
import engine.Sky;
import engine.WorldSnapshot;
import engine.lights.DirectionalLight;
import engine.lights.Light;
import engine.lights.PointLight;

import math.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** Public loader and data model for reproducible sample-game diagnostic captures. */
public final class GameCapture {
    public static final int VERSION = 2;

    public record Controls(
            RenderSettings settings,
            GameLighting lighting,
            float exposure,
            double hour,
            double duration,
            boolean cycle,
            long refinementTarget,
            boolean atmosphereEnabled,
            Atmosphere.Parameters atmosphere,
            boolean fogEnabled,
            boolean fogOverlap,
            boolean fogReverse,
            boolean fogDemo,
            boolean fogClosed,
            float fogShift,
            List<FogRegion> fogRegions) {}

    public record LightValue(boolean directional, Vec3 vector, Vec3 color, float strength) {
        Light restore() {
            return directional
                    ? new DirectionalLight(vector, color, strength)
                    : new PointLight(vector, color, strength);
        }
    }

    public record World(int scene, long revision, Sky sky, List<LightValue> lights) {}

    public record State(
            long nanos, World world, Controls controls, GameNavigation.State navigation) {}

    public record Display(
            long nanos,
            long generation,
            long samples,
            RenderView view,
            World world,
            RenderSettings settings,
            float exposure,
            String metrics,
            String image) {}

    public record Result(Vec3 position, engine.CharacterBody.State body, String stateHash) {}

    public record Motion(
            long nanos, double seconds, int scene, GameNavigation.State edit, Result expected) {}

    public record Timeline(
            List<Motion> motions, List<State> states, List<Display> pictures, long dropped) {}

    public record Manifest(
            int version,
            String createdUtc,
            String build,
            String backend,
            String device,
            String platform,
            int sceneCount,
            State current,
            Display displayed,
            boolean history) {}

    public record Loaded(
            Path directory,
            Manifest manifest,
            Timeline timeline,
            List<List<SceneInstance>> scenes) {
        public WorldSnapshot world(World value) {
            return new WorldSnapshot(
                    value.revision(),
                    scenes.get(value.scene()),
                    List.of(),
                    value.lights().stream().map(LightValue::restore).toList(),
                    value.sky());
        }
    }

    record Pending(
            WorldSnapshot world,
            Controls controls,
            GameNavigation.State navigation,
            GameRenderer.Frame displayed,
            GameRecording.Frozen history,
            String backend,
            String device,
            long nanos,
            boolean includeHistory,
            String displayStalls) {}

    private GameCapture() {}

    private static final class SceneTable {
        final List<List<SceneInstance>> values = new ArrayList<>();
        // Recorder snapshots retain immutable lists. Identity avoids rehashing every mesh for
        // every motion checkpoint; equal distinct lists encode as inexpensive unchanged deltas.
        final java.util.Map<List<SceneInstance>, Integer> indices =
                new java.util.IdentityHashMap<>();

        int size() {
            return values.size();
        }
    }

    static Path write(Path root, Pending pending) throws IOException {
        Files.createDirectories(root);
        var directory =
                root.resolve(
                        java.time.Instant.now().toString().replace(':', '-')
                                + "-"
                                + java.util.UUID.randomUUID());
        Files.createDirectory(directory);
        Files.writeString(
                directory.resolve("INCOMPLETE"),
                "Export in progress; do not load until capture.properties exists and INCOMPLETE is"
                        + " removed.");
        var budget = new CaptureCodec.Budget();
        var scenes = new SceneTable();
        var current =
                state(
                        pending.nanos(),
                        pending.world(),
                        pending.controls(),
                        pending.navigation(),
                        scenes);
        var displayed = display(pending.displayed(), "displayed.png", directory, scenes);
        var motions = new ArrayList<Motion>();
        GameNavigation.State previous = null;
        for (var motion : pending.history().motions()) {
            var edit = motion.before().equals(previous) ? null : motion.before();
            motions.add(
                    new Motion(
                            motion.nanos(),
                            motion.seconds(),
                            scene(scenes, motion.collision()),
                            edit,
                            new Result(
                                    motion.after().position(),
                                    motion.after().body(),
                                    stateHash(motion.after()))));
            previous = motion.after();
        }
        var states = new ArrayList<State>();
        for (var observation : pending.history().states()) {
            states.add(
                    state(
                            observation.nanos(),
                            observation.world(),
                            observation.controls(),
                            observation.navigation(),
                            scenes));
        }
        var pictures = new ArrayList<Display>();
        for (var picture : pending.history().pictures()) {
            var saved =
                    display(
                            picture.frame(),
                            "history-" + pictures.size() + ".png",
                            directory,
                            scenes);
            if (saved != null) {
                pictures.add(saved);
            }
        }
        CaptureScenes.save(directory, scenes.values, budget);
        var timeline = new Timeline(motions, states, pictures, pending.history().dropped());
        CaptureCodec.save(directory.resolve("timeline.properties.gz"), timeline, budget);
        var manifest =
                new Manifest(
                        VERSION,
                        java.time.Instant.now().toString(),
                        GameCrashHandler.buildIdentity(),
                        pending.backend(),
                        pending.device(),
                        System.getProperty("os.name")
                                + " / "
                                + System.getProperty("java.runtime.version"),
                        scenes.size(),
                        current,
                        displayed,
                        pending.includeHistory());
        CaptureCodec.save(directory.resolve("capture.properties"), manifest, budget);
        Files.writeString(directory.resolve("summary.txt"), summary(manifest, timeline));
        Files.writeString(directory.resolve("display-stalls.txt"), pending.displayStalls());
        writeHashes(directory);
        Files.delete(directory.resolve("INCOMPLETE"));
        return directory.toAbsolutePath();
    }

    private static State state(
            long nanos,
            WorldSnapshot world,
            Controls controls,
            GameNavigation.State navigation,
            SceneTable scenes) {
        return new State(nanos, world(world, scenes), controls, navigation);
    }

    private static World world(WorldSnapshot world, SceneTable scenes) {
        if (!world.legacyObjects().isEmpty()) {
            throw new IllegalArgumentException(
                    "Capture requires scene instances; legacy geometry is unsupported");
        }
        var lights =
                world.lights().stream()
                        .map(
                                light -> {
                                    if (light instanceof DirectionalLight sun) {
                                        return new LightValue(
                                                true, sun.direction(), sun.color(), sun.strength());
                                    }
                                    var point = (PointLight) light;
                                    return new LightValue(
                                            false,
                                            point.position(),
                                            point.color(),
                                            point.intensity());
                                })
                        .toList();
        return new World(scene(scenes, world.instances()), world.revision(), world.sky(), lights);
    }

    private static int scene(SceneTable scenes, List<SceneInstance> value) {
        var index = scenes.indices.get(value);
        if (index != null) {
            return index;
        }
        if (scenes.size() >= CaptureScenes.MAX_SCENES) {
            throw new IllegalArgumentException("Capture exceeds bounded recorder scene capacity");
        }
        scenes.indices.put(value, scenes.size());
        scenes.values.add(value);
        return scenes.size() - 1;
    }

    private static Display display(
            GameRenderer.Frame frame, String name, Path directory, SceneTable scenes)
            throws IOException {
        if (frame == null) {
            return null;
        }
        ImageIO.write(frame.image(), "png", directory.resolve(name).toFile());
        var context = frame.context();
        return new Display(
                frame.publicationNanos(),
                frame.generation(),
                frame.samples(),
                new RenderView(frame.camera(), frame.image().getWidth(), frame.image().getHeight()),
                context == null ? null : world(context.world(), scenes),
                context == null ? null : context.settings(),
                context == null ? 1 : context.exposure(),
                frame.metrics().toString(),
                name);
    }

    public static Loaded load(Path directory) throws IOException {
        try {
            return loadChecked(directory);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid capture content: " + failure.getMessage(), failure);
        }
    }

    private static Loaded loadChecked(Path directory) throws IOException {
        if (Files.exists(directory.resolve("INCOMPLETE"))) {
            throw new IOException("Capture export did not complete");
        }
        var checkedFiles = verifyHashes(directory);
        requireChecked(checkedFiles, "capture.properties");
        requireChecked(checkedFiles, "timeline.properties.gz");
        var budget = new CaptureCodec.Budget();
        var manifest =
                CaptureCodec.load(directory.resolve("capture.properties"), Manifest.class, budget);
        if (manifest.version() < 1 || manifest.version() > VERSION) {
            throw new IOException("Unsupported capture version " + manifest.version());
        }
        if (manifest.sceneCount() < 1 || manifest.sceneCount() > CaptureScenes.MAX_SCENES) {
            throw new IOException("Invalid scene count");
        }
        var scenes = new ArrayList<List<SceneInstance>>();
        if (manifest.version() == 2) {
            requireChecked(checkedFiles, "assets.properties.gz");
            requireChecked(checkedFiles, "scenes.bin.gz");
            scenes.addAll(CaptureScenes.load(directory, manifest.sceneCount(), budget));
        } else {
            if (manifest.sceneCount() > 128) {
                throw new IOException("Invalid legacy scene count");
            }
            for (int i = 0; i < manifest.sceneCount(); i++) {
                requireChecked(checkedFiles, "scene-" + i + ".properties.gz");
                scenes.add(
                        CaptureCodec.load(
                                        directory.resolve("scene-" + i + ".properties.gz"),
                                        CaptureScene.class,
                                        budget)
                                .restore());
            }
        }
        var timeline =
                CaptureCodec.load(
                        directory.resolve("timeline.properties.gz"), Timeline.class, budget);
        if (timeline.motions().size() > GameRecording.MAX_MOTIONS
                || timeline.states().size() > GameRecording.MAX_STATES
                || timeline.pictures().size() > GameRecording.MAX_PICTURES) {
            throw new IOException("History exceeds bounded recorder limits");
        }
        return new Loaded(directory, manifest, timeline, List.copyOf(scenes));
    }

    static String summary(Manifest manifest, Timeline timeline) {
        var nav = manifest.current().navigation();
        return "Game capture v"
                + manifest.version()
                + " at "
                + manifest.createdUtc()
                + "\nBuild: "
                + manifest.build()
                + "\nBackend: "
                + manifest.backend()
                + " | "
                + manifest.device()
                + "\nEye: "
                + nav.position()
                + " | yaw/pitch radians: "
                + nav.yaw()
                + "/"
                + nav.pitch()
                + "\nCamera optics: vertical FOV="
                + nav.fov()
                + " degrees, aperture="
                + nav.aperture()
                + ", focus="
                + nav.focus()
                + "\nWalking: "
                + nav.walking()
                + " | body: "
                + nav.body()
                + "\nControls: "
                + manifest.current().controls()
                + "\nWorld: "
                + manifest.current().world()
                + "\nDisplayed: "
                + manifest.displayed()
                + "\nHistory: "
                + timeline.motions().size()
                + " motion updates, "
                + timeline.states().size()
                + " settings/world observations, "
                + timeline.pictures().size()
                + " images; capacity drops="
                + timeline.dropped()
                + "\n"
                + "Inspect: ./capture.ps1 -Capture <this-folder> -Action inspect\n"
                + "Movement test: ./capture.ps1 -Capture <this-folder> -Action replay\n"
                + "Fresh image: ./capture.ps1 -Capture <this-folder> -Action render -View"
                + " displayed\n"
                + "Current state and displayed image are separate. Render replay starts fresh"
                + " accumulation; scheduling and temporal history are not restored.\n";
    }

    static String stateHash(GameNavigation.State state) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    state.toString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hash(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static void writeHashes(Path directory) throws IOException {
        var lines = new ArrayList<String>();
        try (var files = Files.list(directory)) {
            for (var file :
                    files.filter(path -> !path.getFileName().toString().equals("INCOMPLETE"))
                            .sorted()
                            .toList()) {
                lines.add(hash(file) + " " + file.getFileName());
            }
        }
        Files.write(directory.resolve("sha256.txt"), lines);
    }

    private static java.util.Set<String> verifyHashes(Path directory) throws IOException {
        var verified = new java.util.HashSet<String>();
        long totalBytes = 0;
        var checksum = directory.resolve("sha256.txt");
        if (Files.size(checksum) > 64 * 1024) {
            throw new IOException("Oversized checksum manifest");
        }
        for (var line : Files.readAllLines(checksum)) {
            var fields = line.split(" ", 2);
            if (fields.length != 2 || !fields[1].matches("[a-zA-Z0-9.-]+")) {
                throw new IOException("Invalid checksum entry");
            }
            var file = directory.resolve(fields[1]);
            totalBytes += Files.size(file);
            if (!verified.add(fields[1]) || totalBytes > 256L * 1024 * 1024) {
                throw new IOException("Duplicate file or bundle exceeds 256 MiB");
            }
            if (Files.isSymbolicLink(file)
                    || Files.size(file) > 96L * 1024 * 1024
                    || !hash(file).equals(fields[0])) {
                throw new IOException("Capture checksum mismatch: " + fields[1]);
            }
        }
        return verified;
    }

    private static void requireChecked(java.util.Set<String> verified, String name)
            throws IOException {
        if (!verified.contains(name)) {
            throw new IOException("Missing checksum for " + name);
        }
    }
}
