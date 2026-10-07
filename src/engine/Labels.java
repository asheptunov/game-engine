package engine;

final class Labels {
    static final int MAX_LENGTH=256;
    private Labels(){}
    static String checked(String value) {
        if(value==null||value.isBlank())throw new IllegalArgumentException("Label cannot be blank");
        if(value.length()>MAX_LENGTH)throw new IllegalArgumentException("Label cannot exceed "+MAX_LENGTH+" characters");
        for(int i=0;i<value.length();) {
            char ch=value.charAt(i);int code;
            if(Character.isHighSurrogate(ch)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("Label is not valid XML text");code=Character.toCodePoint(ch,value.charAt(i+1));i+=2;}
            else {if(Character.isLowSurrogate(ch))throw new IllegalArgumentException("Label is not valid XML text");code=ch;i++;}
            if(Character.isISOControl(code)||code==0xfffe||code==0xffff)throw new IllegalArgumentException("Label is not valid XML text");
        }
        return value;
    }
}
