package game;

import engine.Camera;
import engine.RenderView;
import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.objects.Rect;

import math.Vec3;

import java.util.EnumSet;

/** Game-owned flight state. The short monitor protects input and immutable view capture only. */
public final class GameNavigation {
    private static final float SPEED = 3;
    private static final double PITCH_LIMIT = Math.toRadians(89);
    private static final Vec3 SPAWN = new Vec3(0, 2.4f, -6);
    private final EnumSet<KeyCode> held = EnumSet.noneOf(KeyCode.class);
    private Vec3 position = SPAWN;
    private double yaw;
    private double pitch = -.14;
    private boolean captured;
    private boolean active = true;
    private int width = 960;
    private int height = 600;

    public synchronized void capture() {
        if (active) {
            captured = true;
            held.clear();
        }
    }

    public synchronized void release() {
        captured = false;
        held.clear();
    }

    public synchronized void active(boolean value) {
        active = value;
        release();
    }

    public synchronized boolean active() {
        return active;
    }

    public synchronized boolean captured() {
        return captured;
    }

    public synchronized void key(KeyInput input) {
        if (input.key() == KeyCode.ESCAPE && input.pressed()) {
            release();
            return;
        }
        if (input.key() == KeyCode.R && input.pressed()) {
            reset();
            return;
        }
        if (!captured) {
            return;
        }
        if (input.pressed()) {
            held.add(input.key());
        } else {
            held.remove(input.key());
        }
    }

    public synchronized void reset() {
        position = SPAWN;
        yaw = 0;
        pitch = -.14;
        held.clear();
    }

    /** Positive screen X turns right; positive screen Y looks down. */
    public synchronized void look(int dx, int dy) {
        if (captured) {
            yaw = Math.IEEEremainder(yaw + dx * .003, Math.PI * 2);
            pitch = Math.clamp(pitch - dy * .003, -PITCH_LIMIT, PITCH_LIMIT);
        }
    }

    /** Seconds of elapsed wall time, capped after a stall to avoid a large teleport. */
    public synchronized void advance(double seconds) {
        if (!active || !captured) {
            return;
        }
        double forward = axis(KeyCode.W, KeyCode.S);
        double right = axis(KeyCode.D, KeyCode.A);
        double vertical =
                (held.contains(KeyCode.SPACE) ? 1 : 0)
                        - (held.contains(KeyCode.L_CTRL) || held.contains(KeyCode.R_CTRL) ? 1 : 0);
        double length = Math.sqrt(forward * forward + right * right + vertical * vertical);
        if (length == 0) {
            return;
        }
        double distance = SPEED * Math.clamp(seconds, 0, .25) / length;
        position =
                position.add(
                        new Vec3(
                                (float)
                                        ((forward * Math.sin(yaw) + right * Math.cos(yaw))
                                                * distance),
                                (float) (vertical * distance),
                                (float)
                                        ((forward * Math.cos(yaw) - right * Math.sin(yaw))
                                                * distance)));
    }

    private int axis(KeyCode positive, KeyCode negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }

    public synchronized void resize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public synchronized RenderView view() {
        float aspect = (float) width / height;
        var forward =
                new Vec3(
                        (float) (Math.sin(yaw) * Math.cos(pitch)),
                        (float) Math.sin(pitch),
                        (float) (Math.cos(yaw) * Math.cos(pitch)));
        var right = new Vec3((float) Math.cos(yaw), 0, (float) -Math.sin(yaw));
        var up = forward.cross(right);
        // Sensor one unit ahead, with a fixed 60-degree vertical field of view.
        float sensorHeight = (float) (2 * Math.tan(Math.toRadians(30)));
        var horizontal = right.scale(sensorHeight * aspect);
        var vertical = up.scale(sensorHeight);
        var origin = position.add(forward).sub(horizontal.scale(.5f)).sub(vertical.scale(.5f));
        var camera = new Camera(position, new Rect(origin, horizontal, vertical)).validated();
        int traceWidth = Math.clamp(Math.round(200 * aspect), 64, 1600);
        int traceHeight = Math.clamp(Math.round(traceWidth / aspect), 64, 1600);
        return new RenderView(camera, traceWidth, traceHeight);
    }
}
