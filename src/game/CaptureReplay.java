package game;

import engine.CapsuleCollision;
import engine.RenderEngine;

import java.nio.file.Path;

import javax.imageio.ImageIO;

/** Headless inspection, fresh-image reproduction and deterministic navigation regression checks. */
public final class CaptureReplay {
    private CaptureReplay() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: CaptureReplay inspect|replay|render <capture-folder> [output.png]"
                            + " [cpu|gpu] [current|displayed] [samples]");
        }
        var capture = GameCapture.load(Path.of(args[1]));
        switch (args[0]) {
            case "inspect" ->
                    System.out.println(GameCapture.summary(capture.manifest(), capture.timeline()));
            case "replay" -> System.out.println(replay(capture));
            case "render" ->
                    render(
                            capture,
                            Path.of(args.length > 2 ? args[2] : "out/game/capture-render.png"),
                            args.length > 3 ? args[3] : capture.manifest().backend(),
                            args.length > 4 && args[4].equals("displayed"),
                            args.length > 5 ? Integer.parseInt(args[5]) : 1);
            default -> throw new IllegalArgumentException("Unknown capture action: " + args[0]);
        }
    }

    public static String replay(GameCapture.Loaded capture) {
        var steps = capture.timeline().motions();
        if (steps.isEmpty()) {
            return "NO MOTION: capture has no recorded movement updates to verify.";
        }
        var navigation = new GameNavigation();
        int previousScene = -1;
        int edits = 0;
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            if (step.scene() != previousScene) {
                var instances = capture.scenes().get(step.scene());
                navigation.collision(
                        instances.stream()
                                        .allMatch(
                                                instance ->
                                                        instance.geometry()
                                                                instanceof engine.PolygonMesh)
                                ? new CapsuleCollision(instances)
                                : null);
                previousScene = step.scene();
            }
            if (step.edit() != null) {
                navigation.restore(step.edit());
                edits++;
            }
            if (index == 0 && step.edit() == null) {
                throw new IllegalArgumentException("History lacks initial checkpoint");
            }
            navigation.advance(step.seconds());
            var actual = navigation.snapshot();
            if (!GameCapture.stateHash(actual).equals(step.expected().stateHash())) {
                throw new IllegalStateException(
                        "REPLAY DIVERGED at update "
                                + index
                                + ", timestamp="
                                + step.nanos()
                                + "\nExpected eye="
                                + step.expected().position()
                                + " body="
                                + step.expected().body()
                                + "\nActual="
                                + actual);
            }
        }
        return "PASS: "
                + steps.size()
                + " movement updates; "
                + edits
                + " input/control checkpoints. All resulting navigation/physics states match"
                + " exactly.\n"
                + "Renderer scheduling and asynchronous autofocus acquisition are not replayed;"
                + " their recorded effects are checkpoints.";
    }

    public static void render(
            GameCapture.Loaded capture, Path output, String backend, boolean displayed, int samples)
            throws Exception {
        if (samples < 1 || samples > 4096) {
            throw new IllegalArgumentException("Samples must be 1..4096");
        }
        var state = capture.manifest().current();
        var world = capture.world(state.world());
        var navigation = new GameNavigation();
        navigation.collision(
                world.instances().stream()
                                .allMatch(
                                        instance ->
                                                instance.geometry() instanceof engine.PolygonMesh)
                        ? new CapsuleCollision(world.instances())
                        : null);
        navigation.restore(state.navigation());
        var view = navigation.view();
        var settings = state.controls().settings();
        float exposure = state.controls().exposure();
        if (displayed) {
            var shown = capture.manifest().displayed();
            if (shown == null || shown.world() == null) {
                throw new IllegalStateException("No displayed image with known render context");
            }
            world = capture.world(shown.world());
            view = shown.view();
            settings = shown.settings();
            exposure = shown.exposure();
        }
        settings =
                settings.withPaused(false)
                        .withSampleTarget(samples)
                        .withSamplesPerBatch(1)
                        .withInteractive(false, settings.interactiveMillis(), 0, 0)
                        .withTemporal(false, settings.temporalRevision());
        java.nio.file.Files.createDirectories(output.toAbsolutePath().getParent());
        try (var session = RenderEngine.openSession(world, view, settings, backend)) {
            writeImage(session, output, exposure, samples);
            System.out.println(
                    "Rendered "
                            + (displayed ? "displayed" : "current")
                            + " state with "
                            + backend
                            + ", "
                            + samples
                            + " spp: "
                            + output);
        }
    }

    private static void writeImage(
            engine.RenderSession session, Path output, float exposure, int samples)
            throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            session.request();
            if (session.failure() != null) {
                throw session.failure();
            }
            var image = session.acquireImage();
            if (image != null) {
                try (image) {
                    if (image.samples() >= samples) {
                        ImageIO.write(
                                GameRenderer.copyImage(image, exposure), "png", output.toFile());
                        return;
                    }
                }
            }
            Thread.sleep(2);
        }
        throw new IllegalStateException("Capture render timed out after 120 seconds");
    }
}
