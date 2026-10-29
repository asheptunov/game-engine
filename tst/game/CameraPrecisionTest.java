package game;

import static harness.Assertions.assertTrue;

import engine.Camera;
import engine.objects.Rect;

import harness.SuiteRunner;
import harness.Test;

import math.Vec3;

public final class CameraPrecisionTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void depthOfFieldSurvivesLookingAroundAwayFromWorldOrigin() {
        var navigation = new GameNavigation();
        navigation.resize(3840, 2160);
        navigation.optics(.01f, 8.469296f);
        for (var position :
                new Vec3[] {
                    new Vec3(8.95015f, -2.3017077f, 66.676895f),
                    new Vec3(-120, 5, -120),
                    new Vec3(512, -32, 512)
                }) {
            for (int angle = 0; angle < 720; angle++) {
                for (double pitch : new double[] {-.8, -.07, .8}) {
                    navigation.pose(position, angle * Math.PI / 360, pitch);
                    var camera = navigation.view().camera();
                    assertTrue(camera.eye().equals(position));
                    assertTrue(camera.aperture() == .01f);
                    assertTrue(camera.withProjection("orthographic").aperture() == .01f);
                }
            }
        }
    }

    @Test
    void scopeTransitionAllowsSubCoordinateApertures() {
        var navigation = new GameNavigation();
        navigation.resize(3840, 2160);
        navigation.pose(new Vec3(9.350019f, -2.494629f, 56.425514f), 1.339f, 0);
        navigation.optics(.01f, 25);
        navigation.capture();
        for (boolean aiming : new boolean[] {true, false, true, false}) {
            navigation.scoped(aiming);
            for (int frame = 0; frame < 240; frame++) {
                navigation.advanceAim(1.0 / 240);
                var camera = navigation.view().camera();
                assertTrue(Float.isFinite(camera.aperture()));
                assertTrue(camera.aperture() >= 0);
            }
            assertTrue(navigation.view().camera().aperture() == (aiming ? 0 : .01f));
        }
    }

    @Test
    void realOffsetAndSkewStillRejectAtTranslatedPositions() {
        var navigation = new GameNavigation();
        for (var position : new Vec3[] {Vec3.ZERO, new Vec3(512, -32, 512)}) {
            navigation.pose(position, 1.4, -.07);
            var camera = navigation.view().camera();
            var plane = camera.sensor();
            reject(
                    new Camera(
                            position,
                            new Rect(
                                    plane.origin().add(plane.edge1().normalized().scale(.01f)),
                                    plane.edge1(),
                                    plane.edge2())));
            reject(
                    new Camera(
                            position,
                            new Rect(
                                    plane.origin(),
                                    plane.edge1(),
                                    plane.edge2().add(plane.edge1().scale(.01f)))));
        }
    }

    private static void reject(Camera camera) {
        try {
            camera.withAperture(.01f);
            throw new AssertionError("Genuinely offset/skewed camera accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("off-center/skewed"));
        }
    }
}
