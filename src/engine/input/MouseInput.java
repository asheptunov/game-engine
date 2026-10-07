package engine.input;

import java.util.Objects;

/** Platform-neutral pointer input. Drag button denotes the currently held button. */
public record MouseInput(MouseButton button, MouseGesture gesture, String mode, Modifiers modifiers,
                         int x, int y, double wheelRotation) {
    public MouseInput {
        Objects.requireNonNull(button);Objects.requireNonNull(gesture);Objects.requireNonNull(modifiers);
        mode=MouseChord.normalizeMode(mode);
        if(!Double.isFinite(wheelRotation))throw new IllegalArgumentException("Wheel rotation must be finite");
    }
    public MouseInput withMode(String value){return new MouseInput(button,gesture,value,modifiers,x,y,wheelRotation);}
}
