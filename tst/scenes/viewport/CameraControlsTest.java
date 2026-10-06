package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;
import math.Vec3;
import engine.objects.Rect;
import static harness.Assertions.*;

public class CameraControlsTest {
    private static final long START = 1_000_000_000L, TICK = 100_000_000L;
    private static ViewportState state() {
        var state = new ViewportState(new Rect(new Vec3(-.5f, -.5f, 0),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0)), 64, 64);
        state.eye(new Vec3(0, 0, -1));
        return state;
    }
    private static void near(float expected, float actual) { assertTrue(Math.abs(expected - actual) < .0001f); }

    @Test void bothKeysStayHeldAndEitherReleaseKeepsTheOtherMoving() {
        for (boolean releaseForward : new boolean[]{false, true}) {
            var state = state(); var controls = new CameraControls();
            controls.press(87, 1, "camera.move.forward", START);
            controls.press(68, 1, "camera.strafe.right", START);
            controls.update(state, START + TICK);
            var delta = state.eye().sub(new Vec3(0, 0, -1));
            near(.3f, delta.length()); near(delta.x(), delta.z());
            controls.release(releaseForward ? 87 : 68, 1);
            var eye = state.eye(); controls.update(state, START + 2 * TICK);
            delta = state.eye().sub(eye);
            near(releaseForward ? .3f : 0, delta.x());
            near(releaseForward ? 0 : .3f, delta.z());
            controls.release(releaseForward ? 68 : 87, 1);
            eye = state.eye(); controls.update(state, START + 3 * TICK);
            assertEquals(eye, state.eye());
        }
    }
    @Test void repeatDoesNotChangeSpeedAndFrameIntervalsAgree() {
        var a = state(); var b = state();
        var first = new CameraControls(); var second = new CameraControls();
        first.press(87, 1, "camera.move.forward", START);
        second.press(87, 1, "camera.move.forward", START);
        for (int i = 1; i <= 10; i++) {
            first.press(87, 1, "camera.move.forward", START + i * TICK);
            first.update(a, START + i * TICK);
        }
        for (int i = 1; i <= 20; i++) second.update(b, START + i * TICK / 2);
        near(3, a.eye().z() + 1); near(a.eye().z(), b.eye().z());
    }
    @Test void oppositeDirectionsCancelAndDualControlKeysReleaseIndependently() {
        var state = state(); var controls = new CameraControls(); var eye = state.eye();
        controls.press(87, 1, "camera.move.forward", START);
        controls.press(83, 1, "camera.move.back", START);
        controls.update(state, START + TICK); assertEquals(eye, state.eye());
        controls.clear();
        controls.press(17, 2, "camera.move.down", START);
        controls.press(17, 3, "camera.move.down", START);
        controls.update(state, START + TICK); near(-.3f, state.eye().y());
        controls.release(17, 2); controls.update(state, START + 2 * TICK);
        near(-.6f, state.eye().y());
        controls.clear(); eye = state.eye(); controls.update(state, START + 3 * TICK);
        assertEquals(eye, state.eye());
    }
    @Test void lookPreservesPinholeGeometryAndMovementFollowsView() {
        var state = state(); var controls = new CameraControls(); var eye = state.eye();
        controls.startLook(0, 0); controls.drag(200, -100); controls.update(state, START);
        assertEquals(eye, state.eye());
        var sensor = state.cameraSensor();
        var forward = sensor.edge1().cross(sensor.edge2()).normalized();
        assertTrue(forward.x() > 0 && forward.y() > 0);
        near(1, sensor.edge1().length()); near(1, sensor.edge2().length());
        near(0, sensor.edge1().dot(sensor.edge2()));
        var center = sensor.origin().add(sensor.edge1().scale(.5f)).add(sensor.edge2().scale(.5f));
        near(1, center.sub(eye).length());
        near(1, center.sub(eye).normalized().dot(forward));
        controls.press(87, 1, "camera.move.forward", START);
        controls.update(state, START + TICK);
        near(1, state.eye().sub(eye).normalized().dot(forward));
        near(0, state.cameraSensor().origin().sub(sensor.origin()).sub(state.eye().sub(eye)).length());
    }
    @Test void pitchIsClampedAndStoppedOrClearedMouseDoesNotRotate() {
        var state = state(); var controls = new CameraControls();
        controls.startLook(0, 0); controls.drag(0, -100000); controls.update(state, START);
        var sensor = state.cameraSensor();
        var forward = sensor.edge1().cross(sensor.edge2()).normalized();
        near((float) Math.sin(Math.toRadians(89)), forward.y());
        controls.stopLook(); controls.drag(200, 0); controls.update(state, START + TICK);
        assertEquals(sensor, state.cameraSensor());
        controls.startLook(0, 0); controls.drag(200, 200); controls.clear();
        controls.update(state, START + 2 * TICK); assertEquals(sensor, state.cameraSensor());
    }
    public static void main(String[] args) { SuiteRunner.runThis(); }
}
