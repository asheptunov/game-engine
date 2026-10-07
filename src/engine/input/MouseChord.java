package engine.input;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** Strict mouse button, gesture, mode and modifier chord. */
public record MouseChord(MouseButton button, MouseGesture gesture, String mode, Modifiers modifiers) {
    public MouseChord {
        Objects.requireNonNull(button);Objects.requireNonNull(gesture);Objects.requireNonNull(modifiers);
        mode=normalizeMode(mode);
    }
    public static MouseChord of(MouseButton button,MouseGesture gesture,String mode){
        return new MouseChord(button,gesture,mode,Modifiers.NONE);
    }
    public static MouseChord from(MouseInput input){
        return new MouseChord(input.button(),input.gesture(),input.mode(),input.modifiers());
    }
    public static MouseChord parse(String text){
        var value=text.trim();if(value.isEmpty())throw new IllegalArgumentException("Empty mouse chord");
        MouseButton button=null;MouseGesture gesture=null;String mode="";
        boolean ctrl=false,alt=false,shift=false,meta=false;
        for(var raw:value.split("\\+")){
            var token=raw.trim().toLowerCase(Locale.ROOT);if(token.isEmpty())continue;
            switch(token){
                case "ctrl" -> ctrl=true;case "alt" -> alt=true;case "shift" -> shift=true;case "meta" -> meta=true;
                case "left" -> button=uniqueButton(button,MouseButton.LEFT,text);case "middle" -> button=uniqueButton(button,MouseButton.MIDDLE,text);
                case "right" -> button=uniqueButton(button,MouseButton.RIGHT,text);case "none" -> button=uniqueButton(button,MouseButton.NONE,text);
                case "press" -> gesture=uniqueGesture(gesture,MouseGesture.PRESS,text);case "release" -> gesture=uniqueGesture(gesture,MouseGesture.RELEASE,text);
                case "drag" -> gesture=uniqueGesture(gesture,MouseGesture.DRAG,text);case "move" -> gesture=uniqueGesture(gesture,MouseGesture.MOVE,text);
                case "wheel" -> gesture=uniqueGesture(gesture,MouseGesture.WHEEL,text);
                default -> {if(!mode.isEmpty())throw new IllegalArgumentException("Multiple modes in mouse chord '"+text+"'");mode=token;}
            }
        }
        if(gesture==null)throw new IllegalArgumentException("No gesture in mouse chord '"+text+"'");
        return new MouseChord(button==null?MouseButton.NONE:button,gesture,mode,new Modifiers(ctrl,alt,shift,meta));
    }
    public String format(){
        var tokens=new ArrayList<String>();
        if(modifiers.ctrl())tokens.add("ctrl");if(modifiers.alt())tokens.add("alt");
        if(modifiers.shift())tokens.add("shift");if(modifiers.meta())tokens.add("meta");
        if(button!=MouseButton.NONE)tokens.add(button.name().toLowerCase(Locale.ROOT));
        tokens.add(gesture.name().toLowerCase(Locale.ROOT));if(!mode.isEmpty())tokens.add(mode);
        return String.join("+",tokens);
    }
    private static MouseButton uniqueButton(MouseButton current,MouseButton next,String text){
        if(current!=null&&current!=next)throw new IllegalArgumentException("Multiple buttons in mouse chord '"+text+"'");
        return next;
    }
    private static MouseGesture uniqueGesture(MouseGesture current,MouseGesture next,String text){
        if(current!=null&&current!=next)throw new IllegalArgumentException("Multiple gestures in mouse chord '"+text+"'");
        return next;
    }
    private static boolean isReserved(String mode){
        return switch(mode){case "ctrl","alt","shift","meta","left","middle","right","none","press","release","drag","move","wheel" -> true;default -> false;};
    }
    static String normalizeMode(String value){
        var mode=value==null?"":value.toLowerCase(Locale.ROOT);
        if(!mode.isEmpty()&&(!mode.matches("[a-z0-9_.-]+")||isReserved(mode)))
            throw new IllegalArgumentException("Invalid mouse mode '"+mode+"'");
        return mode;
    }
}
