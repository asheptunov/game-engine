package game;

import engine.*;
import engine.objects.Rect;

import math.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Matched capture fixture; updates the player optic and its supports, never the capture archive.
 */
public final class ScopeCaptureScene {
    public record Frame(
            WorldSnapshot world, RenderView view, RenderSettings settings, float exposure) {}

    public static Frame load(Path path, boolean gameplay) throws Exception {
        var capture = GameCapture.load(path);
        var shown = capture.manifest().displayed();
        var world = capture.world(shown.world());
        if (gameplay) {
            String prefix = "player/rifle/scope/";
            var pose =
                    world.instances().stream()
                            .filter(p -> p.name().equals(prefix + "housing"))
                            .findFirst()
                            .orElseThrow()
                            .transform();
            var objects = new ArrayList<>(world.instances());
            objects.removeIf(
                    p -> p.name().startsWith(prefix) || p.name().startsWith("player/rifle/body/"));
            objects.addAll(
                    RifleMesh.instances("player/rifle/", pose, 0).stream()
                            .filter(
                                    p ->
                                            p.name().startsWith(prefix)
                                                    || p.name().startsWith("player/rifle/body/"))
                            .toList());
            world =
                    new WorldSnapshot(
                            world.revision(),
                            objects,
                            world.legacyObjects(),
                            world.lights(),
                            world.sky());
        }
        return new Frame(world, shown.view(), shown.settings(), shown.exposure());
    }

    public static Frame translated(Frame frame, float x) {
        var delta = new Vec3(x, 0, 0);
        var camera = frame.view().camera();
        var sensor = camera.sensor();
        var moved =
                new Camera(
                        camera.eye().add(delta),
                        new Rect(sensor.origin().add(delta), sensor.edge1(), sensor.edge2()));
        var objects =
                frame.world().instances().stream()
                        .map(
                                p ->
                                        p.name().startsWith("player/")
                                                ? p.withTransform(
                                                        new Transform(
                                                                p.transform().position.add(delta),
                                                                p.transform().rotation,
                                                                p.transform().scale))
                                                : p)
                        .toList();
        var world =
                new WorldSnapshot(
                        frame.world().revision() + Math.round(x * 10000),
                        objects,
                        frame.world().legacyObjects(),
                        frame.world().lights(),
                        frame.world().sky());
        return new Frame(
                world,
                new RenderView(moved, frame.view().width(), frame.view().height()),
                frame.settings(),
                frame.exposure());
    }
}
