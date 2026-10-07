package engine.input;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** Strict physical key and modifier chord. */
public record KeyChord(KeyCode key, Modifiers modifiers) {
    public KeyChord {
        Objects.requireNonNull(key);
        Objects.requireNonNull(modifiers);
        if(key==KeyCode.UNKNOWN)throw new IllegalArgumentException("UNKNOWN cannot be bound");
    }
    public static KeyChord of(KeyCode key){return new KeyChord(key,Modifiers.NONE);}
    public static KeyChord ctrl(KeyCode key){return new KeyChord(key,new Modifiers(true,false,false,false));}
    public static KeyChord ctrlShift(KeyCode key){return new KeyChord(key,new Modifiers(true,false,true,false));}
    public static KeyChord from(KeyInput input){return new KeyChord(input.key(),input.modifiers());}
    public static KeyChord parse(String text){
        var value=text.trim();if(value.isEmpty())throw new IllegalArgumentException("Empty key chord");
        String keyToken,modifierText;
        if(value.endsWith("+")){keyToken="+";modifierText=value.substring(0,value.length()-1);}
        else {int split=value.lastIndexOf('+');keyToken=value.substring(split+1);modifierText=split<0?"":value.substring(0,split);}
        boolean ctrl=false,alt=false,shift=false,meta=false;
        for(var raw:modifierText.split("\\+")){
            if(raw.isBlank())continue;
            switch(raw.trim().toLowerCase(Locale.ROOT)){
                case "ctrl" -> ctrl=true;case "alt" -> alt=true;case "shift" -> shift=true;case "meta" -> meta=true;
                default -> throw new IllegalArgumentException("Unknown modifier '"+raw+"' in key chord '"+text+"'");
            }
        }
        if(keyToken.equals("+")){keyToken="=";shift=true;}
        return new KeyChord(KeyCode.parse(keyToken),new Modifiers(ctrl,alt,shift,meta));
    }
    public String format(){
        var tokens=new ArrayList<String>();
        if(modifiers.ctrl())tokens.add("ctrl");if(modifiers.alt())tokens.add("alt");
        boolean plus=key==KeyCode.EQUAL && modifiers.shift();
        if(modifiers.shift()&&!plus)tokens.add("shift");if(modifiers.meta())tokens.add("meta");
        tokens.add(plus?"+":key.token());
        return String.join("+",tokens);
    }
}
