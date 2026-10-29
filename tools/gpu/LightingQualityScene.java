package game;

import engine.*;

import math.Vec3;

import java.util.*;

/** Deterministic frozen combat states shared by every quality mode and its reference. */
public final class LightingQualityScene {
    public record Frame(WorldSnapshot world, RenderView view) {}

    public static List<Frame> frames() {
        var base = AdventureWorld.create();
        var light = new DayNightCycle().atmosphericLighting(Atmosphere.Parameters.clearAir());
        var shooter = new ShooterGame(base.instances());
        var navigation = new GameNavigation();
        navigation.fixedResolution(512, 288);
        navigation.optics(0, 12);
        var frames = new ArrayList<Frame>();
        for (int frame = 0; frame < 12; frame++) {
            navigation.pose(new Vec3(frame * .035f, 2.4f, -6 + frame * .02f), frame * .004, -.08);
            var view = navigation.view();
            shooter.advance(.05, view.camera(), true);
            if (frame == 4) shooter.fire(view.camera(), 0f);
            float aim = frame >= 8 ? 1 : 0;
            var objects = new ArrayList<>(base.instances());
            objects.addAll(shooter.instances());
            objects.addAll(RifleMesh.firstPerson(view.camera(), shooter.status().cooldown(), aim));
            frames.add(
                    new Frame(
                            new WorldSnapshot(
                                    frame, objects, List.of(), List.of(light.sun()), light.sky()),
                            view));
        }
        return frames;
    }
}
