package game;

import engine.Camera;
import engine.RenderView;
import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.objects.Rect;

import math.Vec3;

import java.util.EnumSet;

/** Game-owned input, flight and character state. Physics and view capture share this monitor. */
public final class GameNavigation {
    private float speed = 3;
    private engine.CapsuleCollision collision;
    private final engine.CharacterBody character = new engine.CharacterBody(SPAWN);
    private boolean walking;
    private boolean jumpPending;
    private double physicsRemainder;

    private GameRecording recording;
    private java.util.List<engine.SceneInstance> collisionSource = java.util.List.of();

    public record State(
            Vec3 position,
            double yaw,
            double pitch,
            float speed,
            double sensitivity,
            float fov,
            float aperture,
            float focus,
            boolean autofocus,
            long focusRevision,
            boolean walking,
            boolean jumpPending,
            double physicsRemainder,
            engine.CharacterBody.State body,
            boolean captured,
            boolean active,
            int width,
            int height,
            int fixedWidth,
            int fixedHeight,
            java.util.List<KeyCode> held,
            WeaponState weapon) {
        public State {
            held = java.util.List.copyOf(held);
            java.util.Objects.requireNonNull(body);
            java.util.Objects.requireNonNull(weapon);
            range(position.x(), -Float.MAX_VALUE, Float.MAX_VALUE, "Position X");
            range(position.y(), -Float.MAX_VALUE, Float.MAX_VALUE, "Position Y");
            range(position.z(), -Float.MAX_VALUE, Float.MAX_VALUE, "Position Z");
            range(yaw, -Double.MAX_VALUE, Double.MAX_VALUE, "Yaw");
            range(pitch, -PITCH_LIMIT, PITCH_LIMIT, "Pitch");
            range(speed, .1, 100, "Speed");
            range(sensitivity, .0001, .05, "Sensitivity");
            range(fov, 1, 179, "Field of view");
            range(aperture, 0, 10, "Aperture");
            range(focus, .01, 10000, "Focus");
            range(physicsRemainder, -1e-8, .25, "Physics remainder");
            range(width, 1, 16384, "Window width");
            range(height, 1, 16384, "Window height");
            if (fixedWidth != 0 || fixedHeight != 0) {
                RenderView.validateDimensions(fixedWidth, fixedHeight);
            }
        }
    }

    public synchronized State snapshot() {
        return new State(
                position,
                yaw,
                pitch,
                speed,
                sensitivity,
                fov,
                aperture,
                focus,
                autofocus,
                focusRevision,
                walking,
                jumpPending,
                physicsRemainder,
                character.snapshot(),
                captured,
                active,
                width,
                height,
                fixedWidth,
                fixedHeight,
                java.util.List.copyOf(held),
                new WeaponState(combat, weaponTime, recoil));
    }

    public synchronized void restore(State state) {
        if (state.walking() && collision == null) {
            throw new IllegalStateException("Walking snapshot needs collision geometry");
        }
        position = state.position();
        yaw = state.yaw();
        pitch = state.pitch();
        speed = state.speed();
        sensitivity = state.sensitivity();
        fov = state.fov();
        aperture = state.aperture();
        focus = state.focus();
        autofocus = state.autofocus();
        focusRevision = state.focusRevision();
        walking = state.walking();
        jumpPending = state.jumpPending();
        physicsRemainder = state.physicsRemainder();
        character.restore(state.body());
        captured = state.captured();
        active = state.active();
        width = state.width();
        height = state.height();
        fixedWidth = state.fixedWidth();
        fixedHeight = state.fixedHeight();
        held.clear();
        held.addAll(state.held());
        combat = state.weapon().combat();
        weaponTime = state.weapon().time();
        recoil = state.weapon().recoil();
        view().camera().validated();
    }

    synchronized void recording(GameRecording value) {
        recording = value;
    }

    synchronized void collisionSource(java.util.List<engine.SceneInstance> value) {
        collisionSource = value;
    }

    public synchronized boolean walking() {
        return walking;
    }

    public synchronized void walking(boolean enabled) {
        if (enabled && collision == null) {
            throw new IllegalStateException("No mesh collision world loaded");
        }
        walking = enabled;
        character.teleport(position);
        held.clear();
        jumpPending = false;
        physicsRemainder = 0;
    }

    public synchronized void collision(engine.CapsuleCollision value) {
        collision = value;
    }

    public synchronized boolean grounded() {
        return walking && character.grounded();
    }

    private double sensitivity = .003;
    private float fov = 60;
    private float aperture;
    private float focus = 5;
    private boolean autofocus;
    private boolean scoped;
    private boolean combat;
    private double weaponTime;
    private double recoil;

    public record WeaponState(boolean combat, double time, double recoil) {
        public WeaponState {
            range(time, 0, Double.MAX_VALUE, "Weapon time");
            range(recoil, 0, .07, "Recoil radians");
        }
    }

    synchronized void combat(boolean value) {
        combat = value;
    }

    public synchronized void recoil() {
        recoil = Math.min(.07, recoil + .035 * stability());
    }

    public synchronized String stance() {
        return character.targetHeight() == .65f
                ? "PRONE"
                : character.targetHeight() == 1.2f ? "CROUCHED" : "STANDING";
    }

    synchronized float bodyHeight() {
        return walking ? character.height() : engine.CharacterBody.HEIGHT;
    }

    private float stability() {
        return .3f + .7f * (bodyHeight() - .65f) / 1.15f;
    }

    private final AimMotion aimMotion = new AimMotion();

    public synchronized float aimAmount() {
        return aimMotion.amount();
    }

    private float scopeFocus = 25;

    public synchronized void scoped(boolean value) {
        scoped = value && captured;
    }

    public synchronized boolean scoped() {
        return scoped;
    }

    public synchronized void scopeFocus(int clicks) {
        scopeFocus = Math.clamp(scopeFocus * (float) Math.pow(1.12, clicks), 2, 250);
    }

    public synchronized float scopeFocus() {
        return scopeFocus;
    }

    private long focusRevision;
    private static final double PITCH_LIMIT = Math.toRadians(89);
    private static final Vec3 SPAWN = new Vec3(0, 2.4f, -6);
    private final EnumSet<KeyCode> held = EnumSet.noneOf(KeyCode.class);
    private Vec3 position = SPAWN;
    private double yaw;
    private double pitch = -.14;
    private boolean captured;
    private boolean active = true;
    private int fixedWidth;
    private int fixedHeight;
    private int width = 960;
    private int height = 600;

    public synchronized void capture() {
        if (active) {
            captured = true;
            held.clear();
        }
    }

    public synchronized void release() {
        scoped = false;
        aimMotion.reset();
        captured = false;
        recoil = 0;
        weaponTime = 0;
        held.clear();
        jumpPending = false;
        physicsRemainder = 0;
    }

    public synchronized void active(boolean value) {
        if (active != value) {
            focusRevision++;
        }
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
            boolean fresh = held.add(input.key());
            if (fresh && walking) {
                walkingKey(input.key());
            }
        } else {
            held.remove(input.key());
        }
    }

    private void walkingKey(KeyCode key) {
        if (key == KeyCode.C || key == KeyCode.Z) {
            float height = key == KeyCode.C ? 1.2f : .65f;
            character.stance(
                    character.targetHeight() == height ? engine.CharacterBody.HEIGHT : height);
            jumpPending = false;
        } else if (key == KeyCode.SPACE) {
            jumpPending = true;
        }
    }

    public synchronized void reset() {
        scoped = false;
        aimMotion.reset();
        focusRevision++;
        recoil = 0;
        weaponTime = 0;
        position = SPAWN;
        character.teleport(position);
        jumpPending = false;
        physicsRemainder = 0;
        yaw = 0;
        pitch = -.14;
        held.clear();
    }

    /** Replay poses use world units and radians, with unwrapped yaw interpolated by the caller. */
    public synchronized void pose(Vec3 position, double yaw, double pitch) {
        if (!Float.isFinite(position.x())
                || !Float.isFinite(position.y())
                || !Float.isFinite(position.z())
                || !Double.isFinite(yaw)
                || !Double.isFinite(pitch)
                || Math.abs(pitch) > PITCH_LIMIT) {
            throw new IllegalArgumentException(
                    "Replay pose must be finite with pitch within 89 degrees");
        }
        this.position = position;
        character.teleport(position);
        jumpPending = false;
        physicsRemainder = 0;
        this.yaw = yaw;
        this.pitch = pitch;
        held.clear();
    }

    /** Positive screen X turns right; positive screen Y looks down. */
    public synchronized void look(int dx, int dy) {
        if (captured) {
            double effectiveSensitivity = sensitivity * (1 - .75 * aimMotion.amount());
            yaw = Math.IEEEremainder(yaw + dx * effectiveSensitivity, Math.PI * 2);
            pitch = Math.clamp(pitch - dy * effectiveSensitivity, -PITCH_LIMIT, PITCH_LIMIT);
        }
    }

    /** Seconds of elapsed wall time, capped after a stall to avoid a large teleport. */
    synchronized void advanceAim(double seconds) {
        if (active && captured && Double.isFinite(seconds) && seconds >= 0) {
            aimMotion.advance(seconds, scoped);
        }
    }

    public synchronized void advance(double seconds) {
        advanceAim(seconds);
        if (recording == null) {
            advanceMotion(seconds);
            return;
        }
        var before = snapshot();
        advanceMotion(seconds);
        var after = snapshot();
        if (!before.equals(after)) {
            recording.motion(System.nanoTime(), seconds, before, after, collisionSource);
        }
    }

    private void advanceMotion(double seconds) {
        if (!active || !captured) {
            return;
        }
        if (!Double.isFinite(seconds) || seconds < 0) {
            return;
        }
        if (combat) {
            weaponTime += Math.min(.25, seconds);
        }
        recoil *= Math.exp(-Math.min(.25, seconds) * 9);
        if (recoil < .000001) {
            recoil = 0;
        }
        if (walking) {
            advanceCharacter(seconds);
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
        double distance = speed * Math.clamp(seconds, 0, .25) / length;
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

    private void advanceCharacter(double seconds) {
        double forward = axis(KeyCode.W, KeyCode.S);
        double right = axis(KeyCode.D, KeyCode.A);
        double length = Math.max(1, Math.sqrt(forward * forward + right * right));
        float x = (float) ((forward * Math.sin(yaw) + right * Math.cos(yaw)) * speed / length);
        float z = (float) ((forward * Math.cos(yaw) - right * Math.sin(yaw)) * speed / length);
        physicsRemainder += Math.min(seconds, .25);
        while (physicsRemainder + 1e-12 >= engine.CharacterBody.STEP) {
            character.step(collision, x, z, jumpPending);
            jumpPending = false;
            physicsRemainder -= engine.CharacterBody.STEP;
        }
        position =
                character.eye(
                        (float) Math.clamp(physicsRemainder / engine.CharacterBody.STEP, 0, 1));
        if (position.y() < -100) {
            reset();
        }
    }

    private int axis(KeyCode positive, KeyCode negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }

    public synchronized float speed() {
        return speed;
    }

    public synchronized double sensitivity() {
        return sensitivity;
    }

    public synchronized float fov() {
        return fov;
    }

    public synchronized float aperture() {
        return aperture;
    }

    public synchronized float focus() {
        return focus;
    }

    public synchronized boolean autofocus() {
        return autofocus;
    }

    public synchronized void autofocus(boolean value) {
        autofocus = value;
        focusRevision++;
    }

    synchronized long focusRevision() {
        return focusRevision;
    }

    public synchronized void manualFocus(float distance) {
        optics(aperture, distance);
        autofocus(false);
    }

    /** Reject stale queries, and hits that moved more than 2% of the image from center. */
    synchronized boolean acceptFocus(engine.CameraFocus.Measurement result, float distance) {
        if (!active
                || !autofocus
                || result.epoch() != focusRevision
                || System.nanoTime() - result.captured() > 100_000_000L) {
            return false;
        }
        var camera = view().camera();
        var delta = result.hit().sub(camera.eye());
        float axial = delta.dot(camera.forward());
        if (axial <= 0) {
            return false;
        }
        var offset =
                delta.scale(camera.planeDistance() / axial)
                        .sub(camera.forward().scale(camera.planeDistance()));
        var horizontal = camera.sensor().edge1();
        var vertical = camera.sensor().edge2();
        if (Math.abs(offset.dot(horizontal) / horizontal.lengthSq()) > .02
                || Math.abs(offset.dot(vertical) / vertical.lengthSq()) > .02) {
            return false;
        }
        focus = Math.clamp(distance, .01f, 10000f);
        return true;
    }

    public synchronized void speed(float value) {
        range(value, .1, 100, "Speed");
        speed = value;
    }

    public synchronized void sensitivity(double value) {
        range(value, .0001, .05, "Sensitivity");
        sensitivity = value;
    }

    public synchronized void fov(float value) {
        range(value, 1, 179, "Field of view");
        fov = value;
    }

    public synchronized void optics(float radius, float distance) {
        range(radius, 0, 10, "Aperture radius");
        range(distance, .01, 10000, "Focus distance");
        aperture = radius;
        focus = distance;
    }

    private static void range(double value, double low, double high, String label) {
        if (!Double.isFinite(value) || value < low || value > high) {
            throw new IllegalArgumentException(label + " must be " + low + ".." + high);
        }
    }

    public synchronized void resize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public synchronized void fixedResolution(int width, int height) {
        RenderView.validateDimensions(width, height);
        fixedWidth = width;
        fixedHeight = height;
    }

    public synchronized boolean fixedResolution() {
        return fixedWidth != 0;
    }

    public synchronized RenderView view() {
        float aspect = (float) width / height;
        double sway = combat && captured ? .0018 * stability() : 0;
        boolean moving = axis(KeyCode.W, KeyCode.S) != 0 || axis(KeyCode.D, KeyCode.A) != 0;
        sway *= moving ? 2.5 : 1;
        double viewYaw = yaw + sway * Math.sin(weaponTime * 1.7);
        double viewPitch =
                Math.clamp(
                        pitch + recoil + sway * .7 * Math.sin(weaponTime * 2.3),
                        -PITCH_LIMIT,
                        PITCH_LIMIT);
        var forward =
                new Vec3(
                        (float) (Math.sin(viewYaw) * Math.cos(viewPitch)),
                        (float) Math.sin(viewPitch),
                        (float) (Math.cos(viewYaw) * Math.cos(viewPitch)));
        var right = new Vec3((float) Math.cos(viewYaw), 0, (float) -Math.sin(viewYaw));
        var up = forward.cross(right);
        // Sensor one unit ahead; field of view is vertical.
        // Aim framing gives the physical scope 50% more pixels across at full aim.
        // The glass still supplies its own optical magnification; hip-fire FOV is unchanged.
        float sensorHeight =
                (float) (2 * Math.tan(Math.toRadians(fov / 2))) * (1 - aimMotion.amount() / 3);
        var horizontal = right.scale(sensorHeight * aspect);
        var vertical = up.scale(sensorHeight);
        var origin = position.add(forward).sub(horizontal.scale(.5f)).sub(vertical.scale(.5f));
        var camera =
                new Camera(position, new Rect(origin, horizontal, vertical))
                        .withFocus(focus + (scopeFocus - focus) * aimMotion.amount())
                        .withAperture(aperture * (1 - aimMotion.amount()))
                        .validated();
        int traceWidth = Math.clamp(Math.round(200 * aspect), 64, 1600);
        int traceHeight = Math.clamp(Math.round(traceWidth / aspect), 64, 1600);
        return new RenderView(
                camera,
                fixedWidth == 0 ? traceWidth : fixedWidth,
                fixedHeight == 0 ? traceHeight : fixedHeight);
    }
}
