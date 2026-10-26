package engine;

import math.Vec3;

/** Upright capsule with world-unit, second-based motion. Owned by the simulation thread. */
public final class CharacterBody {
    public static final float RADIUS = .3f;
    public static final float HEIGHT = 1.8f;
    public static final float EYE_HEIGHT = 1.65f;
    public static final float GRAVITY = 9.81f;
    public static final float JUMP_HEIGHT = 1.2f;
    public static final double STEP = 1.0 / 120;
    private Vec3 feet;
    private Vec3 previous;
    private Vec3 velocity = Vec3.ZERO;
    private boolean grounded;

    public CharacterBody(Vec3 eye) {
        teleport(eye);
    }

    public void teleport(Vec3 eye) {
        feet = eye.sub(new Vec3(0, EYE_HEIGHT, 0));
        previous = feet;
        velocity = Vec3.ZERO;
        grounded = false;
    }

    public Vec3 feet() {
        return feet;
    }

    public Vec3 velocity() {
        return velocity;
    }

    public boolean grounded() {
        return grounded;
    }

    public Vec3 eye(float alpha) {
        return previous.scale(1 - alpha).add(feet.scale(alpha)).add(new Vec3(0, EYE_HEIGHT, 0));
    }

    /** Desired horizontal velocity is a motor target; jumping is a one-shot upward impulse. */
    public void step(CapsuleCollision world, float targetX, float targetZ, boolean jump) {
        previous = feet;
        float dt = (float) STEP;
        boolean wasGrounded = grounded;
        float acceleration = grounded ? 35 : 8;
        var change = new Vec3(targetX - velocity.x(), 0, targetZ - velocity.z());
        float length = change.length();
        if (length > acceleration * dt) {
            change = change.scale(acceleration * dt / length);
        }
        velocity = velocity.add(change);
        if (jump && grounded) {
            velocity =
                    new Vec3(
                            velocity.x(),
                            (float) Math.sqrt(2 * GRAVITY * JUMP_HEIGHT),
                            velocity.z());
        }
        velocity = velocity.add(new Vec3(0, -GRAVITY * dt, 0));
        if (velocity.y() < -50) {
            velocity = new Vec3(velocity.x(), -50, velocity.z());
        }
        grounded = false;
        // Bound each translation to a fraction of the radius, including at maximum flight speed.
        int subdivisions = Math.max(1, (int) Math.ceil(velocity.length() * dt / (RADIUS * .25f)));
        for (int part = 0; part < subdivisions; part++) {
            feet = feet.add(velocity.scale(dt / subdivisions));
            resolve(world);
        }
        // Follow descending walkable slopes without repeatedly leaving the ground.
        if (wasGrounded && !jump && !grounded && velocity.y() <= 0) {
            var saved = feet;
            feet = feet.add(new Vec3(0, -.12f, 0));
            resolve(world);
            if (!grounded) {
                feet = saved;
            }
        }
    }

    private void resolve(CapsuleCollision world) {
        for (int iteration = 0; iteration < 12; iteration++) {
            var contact = world.contact(feet, RADIUS, HEIGHT);
            if (contact == null) {
                return;
            }
            var normal = contact.normal();
            feet = feet.add(normal.scale(contact.depth() + .00001f));
            // Slopes up to 50 degrees count as ground. Steeper contacts act as walls.
            if (normal.y() >= .6428f && velocity.dot(normal) <= 0) {
                grounded = true;
            }
            if (normal.y() > 0 && normal.y() < .6428f) {
                normal = new Vec3(normal.x(), 0, normal.z()).normalized();
            }
            float inward = velocity.dot(normal);
            if (inward < 0) {
                velocity = velocity.sub(normal.scale(inward));
            }
        }
    }
}
