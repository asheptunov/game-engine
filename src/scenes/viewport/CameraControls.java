package scenes.viewport;

import math.Vec3;
import scenes.viewport.objects.Rect;
import java.util.HashMap;
import java.util.Map;

/** Held movement is integrated by the render loop, independently of OS key repeat. */
final class CameraControls {
    private static final float SPEED = 3f;
    private static final double SENSITIVITY = .003;
    private final Map<Long, Vec3> held = new HashMap<>();
    private long lastUpdate;
    private boolean dragging;
    private int mouseX, mouseY;
    private double yawDelta, pitchDelta;

    boolean press(int code, int location, String action, long now) {
        var direction = switch (action) {
            case "camera.move.forward" -> new Vec3(0, 0, 1);
            case "camera.move.back" -> new Vec3(0, 0, -1);
            case "camera.strafe.left" -> new Vec3(-1, 0, 0);
            case "camera.strafe.right" -> new Vec3(1, 0, 0);
            case "camera.move.up" -> new Vec3(0, 1, 0);
            case "camera.move.down" -> new Vec3(0, -1, 0);
            default -> null;
        };
        if (direction == null) return false;
        if (held.isEmpty()) lastUpdate = now;
        held.put(key(code, location), direction);
        return true;
    }

    void release(int code, int location) { held.remove(key(code, location)); }
    private static long key(int code, int location) { return ((long) code << 32) | location; }
    void clear() { held.clear(); lastUpdate = 0; dragging = false; yawDelta = pitchDelta = 0; }
    void startLook(int x, int y) { dragging = true; mouseX = x; mouseY = y; }
    void stopLook() { dragging = false; }
    void look(int x, int y) {
        if (!dragging) startLook(x, y);
        else drag(x, y);
    }
    void drag(int x, int y) {
        if (!dragging) return;
        yawDelta += (x - mouseX) * SENSITIVITY;
        pitchDelta -= (y - mouseY) * SENSITIVITY;
        mouseX = x; mouseY = y;
    }

    void update(ViewportState state, long now) {
        var sensor = state.cameraSensor();
        if (yawDelta != 0 || pitchDelta != 0) {
            var forward = sensor.edge1().cross(sensor.edge2()).normalized();
            double pitch = Math.asin(Math.clamp(forward.y(), -1f, 1f));
            double yaw = Math.atan2(forward.x(), forward.z()) + yawDelta;
            pitch = Math.clamp(pitch + pitchDelta, Math.toRadians(-89), Math.toRadians(89));
            var right = new Vec3((float) Math.cos(yaw), 0, (float) -Math.sin(yaw));
            var direction = new Vec3((float) (Math.sin(yaw) * Math.cos(pitch)),
                    (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)));
            var up = direction.cross(right).normalized();
            // Preserve sensor size and eye-to-plane distance (field of view).
            var center = sensor.origin().add(sensor.edge1().scale(.5f)).add(sensor.edge2().scale(.5f));
            float distance = center.sub(state.eye()).dot(forward);
            var horizontal = right.scale(sensor.edge1().length());
            var vertical = up.scale(sensor.edge2().length());
            sensor = new Rect(state.eye().add(direction.scale(distance))
                    .sub(horizontal.scale(.5f)).sub(vertical.scale(.5f)), horizontal, vertical);
            state.cameraSensor(sensor);
            yawDelta = pitchDelta = 0;
        }
        double seconds = lastUpdate == 0 ? 0 : Math.clamp((now - lastUpdate) / 1e9, 0, .25);
        lastUpdate = now;
        if (held.isEmpty()) return;
        var local = Vec3.ZERO;
        // Multiple physical keys bound to one direction do not multiply its speed.
        for (var direction : new java.util.HashSet<>(held.values())) local = local.add(direction);
        if (local.lengthSq() == 0 || seconds == 0) return;
        var right = sensor.edge1().normalized();
        var forward = sensor.edge1().cross(sensor.edge2()).normalized();
        var delta = right.scale(local.x()).add(new Vec3(0, local.y(), 0)).add(forward.scale(local.z()));
        if (delta.lengthSq() == 0) return;
        delta = delta.normalized().scale(SPEED * (float) seconds);
        state.eye(state.eye().add(delta));
        state.cameraSensor(new Rect(sensor.origin().add(delta), sensor.edge1(), sensor.edge2()));
    }
}
