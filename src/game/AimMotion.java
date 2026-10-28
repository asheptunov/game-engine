package game;

/** Critically damped spring in normalized hip-to-sight space; velocity is units per second. */
final class AimMotion {
    private double position;
    private double velocity;

    float amount() {
        return (float) position;
    }

    void reset() {
        position = 0;
        velocity = 0;
    }

    void advance(double seconds, boolean held) {
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return;
        }
        double dt = Math.min(seconds, .25);
        double target = held ? 1 : 0;
        double frequency = held ? 18 : 24;
        double displacement = position - target;
        double impulse = velocity + frequency * displacement;
        double decay = Math.exp(-frequency * dt);
        position = target + (displacement + impulse * dt) * decay;
        velocity = (velocity - frequency * impulse * dt) * decay;
        if (position < 0 || position > 1) {
            position = Math.clamp(position, 0, 1);
            velocity = 0;
        }
        if (Math.abs(position - target) < .0001 && Math.abs(velocity) < .002) {
            position = target;
            velocity = 0;
        }
    }
}
