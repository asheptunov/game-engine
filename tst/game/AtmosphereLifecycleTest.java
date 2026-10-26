package game;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

import engine.Atmosphere;
import engine.RenderEngine;
import engine.RenderSettings;
import engine.WorldSnapshot;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

import java.util.List;

/** Atomic settings, live ownership and publication across rapid atmospheric edits. */
public final class AtmosphereLifecycleTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void settingsResetKeepsOpticsTimeExposureAndCameraResetKeepsAir() throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(64, 64);
        navigation.optics(.02f, 8);
        try (var renderer = new GameRenderer(navigation, AdventureWorld.create(), "cpu")) {
            renderer.timeOfDay(17.8);
            renderer.exposure(2);
            renderer.atmospherePreset(true);
            var air = renderer.atmosphere();
            navigation.reset();
            assertEquals(air, renderer.atmosphere());
            renderer.atmospherePreset(false);
            assertEquals(17.8, renderer.timeOfDay());
            assertEquals(2f, renderer.exposure());
            assertEquals(.02f, navigation.view().camera().aperture());
            var before = renderer.atmosphere();
            boolean rejected = false;
            try {
                renderer.atmosphere(before.edited(Float.NaN, 1, 1, before.absorption()));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected);
            assertEquals(before, renderer.atmosphere());
            renderer.atmosphereEnabled(false);
            assertTrue(!renderer.atmosphereEnabled());
        }
    }

    @Test
    void unsupportedSceneCombinationRejectsWithoutChangingAirControls() {
        var navigation = new GameNavigation();
        navigation.fixedResolution(64, 64);
        var emitter =
                new engine.SceneInstance(
                        "emitter",
                        engine.PolygonMesh.parallelogram(
                                Vec3.ZERO, new Vec3(2, 0, 0), new Vec3(0, 2, 0)),
                        engine.Transform.IDENTITY,
                        new engine.Material("emitter", new Vec3(1, 1, 1))
                                .withEmission(new Vec3(1, 1, 1)));
        var source = WorldSnapshot.of(List.of(emitter), List.of());
        try (var renderer = new GameRenderer(navigation, source, "cpu")) {
            var original = renderer.atmosphere();
            boolean rejected = false;
            try {
                renderer.atmospherePreset(true);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected);
            assertTrue(!renderer.atmosphereEnabled());
            assertEquals(original, renderer.atmosphere());
            double hour = renderer.timeOfDay();
            rejected = false;
            try {
                renderer.fogPreset();
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected);
            assertTrue(!renderer.fogEnabled());
            assertEquals(hour, renderer.timeOfDay());
        }
    }

    @Test
    void inFlightEditsRetainLeasesAndCannotPublishStaleAirAsCurrent() throws Exception {
        sessionEdits("cpu");
    }

    static void sessionEdits(String backend) throws Exception {
        var navigation = new GameNavigation();
        navigation.fixedResolution(64, 64);
        var source = AdventureWorld.create();
        var clock = new DayNightCycle();
        clock.hour(12);
        var clear = world(source, clock, Atmosphere.Parameters.clearAir(), 1);
        var settings = RenderSettings.defaults().withPathDepth(2).withSampleTarget(1);
        try (var session = RenderEngine.openSession(clear, navigation.view(), settings, backend);
                var held = AtmosphereScenes.await(session, 1)) {
            var original = BackendConformance.copy(held);
            for (int i = 0; i < 5; i++) {
                clock.hour(17.8 + i * .1);
                session.update(
                        world(source, clock, Atmosphere.Parameters.hazyMorning(), i + 2),
                        navigation.view(),
                        settings);
                session.request();
            }
            var finalWorld = world(source, clock, Atmosphere.Parameters.clearAir(), 10);
            session.update(finalWorld, navigation.view(), settings);
            try (var image = AtmosphereScenes.await(session, 1)) {
                assertEquals(original, BackendConformance.copy(held));
                assertTrue(image.generation() > held.generation());
                try (var isolated =
                                RenderEngine.openSession(
                                        finalWorld, navigation.view(), settings, backend);
                        var expected = AtmosphereScenes.await(isolated, 1)) {
                    assertEquals(BackendConformance.copy(expected), BackendConformance.copy(image));
                }
            }
            session.suspend();
            session.update(clear, navigation.view(), settings);
            try (var resumed = AtmosphereScenes.await(session, 1)) {
                assertTrue(resumed.samples() == 1);
            }
        }
    }

    private static WorldSnapshot world(
            WorldSnapshot source, DayNightCycle clock, Atmosphere.Parameters p, long revision) {
        var light = clock.atmosphericLighting(p);
        return new WorldSnapshot(
                revision, source.instances(), List.of(), List.of(light.sun()), light.sky());
    }
}
