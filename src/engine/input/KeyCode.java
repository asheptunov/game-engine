package engine.input;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Physical key identity. Letter codes are lowercase and never depend on Caps Lock. */
public enum KeyCode {
    ESCAPE, F1,F2,F3,F4,F5,F6,F7,F8,F9,F10,F11,F12, PRINT, PAUSE,
    GRAVE("`"), ONE("1"), TWO("2"), THREE("3"), FOUR("4"), FIVE("5"), SIX("6"),
    SEVEN("7"), EIGHT("8"), NINE("9"), ZERO("0"), MINUS("-"), EQUAL("="), BACKSPACE,
    INSERT, HOME, PAGE_UP, DELETE, END, PAGE_DOWN, TAB,
    Q("q"),W("w"),E("e"),R("r"),T("t"),Y("y"),U("u"),I("i"),O("o"),P("p"),
    L_BRACKET("["),R_BRACKET("]"),BACKSLASH("\\"),CAPS,
    A("a"),S("s"),D("d"),F("f"),G("g"),H("h"),J("j"),K("k"),L("l"),
    SEMICOLON(";"),SINGLE_QUOTE("'"),ENTER,
    L_SHIFT,Z("z"),X("x"),C("c"),V("v"),B("b"),N("n"),M("m"),
    COMMA(","),PERIOD("."),FORWARD_SLASH("/"),R_SHIFT,
    UP,LEFT,DOWN,RIGHT,L_CTRL,L_WIN,L_ALT,L_META,SPACE,R_META,R_ALT,R_WIN,R_CTRL,
    UNKNOWN;

    private final String token;
    KeyCode(){this.token=name().toLowerCase(Locale.ROOT);}
    KeyCode(String token){this.token=token;}
    public String token(){return token;}

    private static final Map<String,KeyCode> INDEX=index();
    private static Map<String,KeyCode> index(){
        var result=new HashMap<String,KeyCode>();
        for(var key:values())result.put(key.token,key);
        return Map.copyOf(result);
    }
    public static KeyCode parse(String text){
        var key=INDEX.get(text.trim().toLowerCase(java.util.Locale.ROOT));
        if(key==null || key==UNKNOWN)throw new IllegalArgumentException("Unknown key: '"+text+"'");
        return key;
    }
}
