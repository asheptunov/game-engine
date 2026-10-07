package engine.input;

import java.util.Objects;
import java.util.Set;

/** Platform-neutral pointer input. Drag button denotes the currently held button. */
public record MouseInput(MouseButton button, MouseGesture gesture, String mode, Modifiers modifiers,
                         int x, int y, double wheelRotation, Set<KeyCode> heldKeys) {
    public MouseInput {
        Objects.requireNonNull(button);Objects.requireNonNull(gesture);Objects.requireNonNull(modifiers);Objects.requireNonNull(heldKeys);
        mode=MouseChord.normalizeMode(mode);
        if(!Double.isFinite(wheelRotation))throw new IllegalArgumentException("Wheel rotation must be finite");
        heldKeys=Set.copyOf(heldKeys);
    }
    public MouseInput(MouseButton button,MouseGesture gesture,String mode,Modifiers modifiers,int x,int y,double wheelRotation){this(button,gesture,mode,modifiers,x,y,wheelRotation,Set.of());}
    public MouseInput withMode(String value){return new MouseInput(button,gesture,value,modifiers,x,y,wheelRotation,heldKeys);}
    public MouseInput withHeldKeys(Set<KeyCode> value){return new MouseInput(button,gesture,mode,modifiers,x,y,wheelRotation,value);}
}
