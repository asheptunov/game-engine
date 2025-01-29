package ui;

import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.Optional;

import static java.awt.event.KeyEvent.KEY_LOCATION_LEFT;
import static java.awt.event.KeyEvent.KEY_LOCATION_RIGHT;
import static java.awt.event.KeyEvent.KEY_LOCATION_STANDARD;
import static java.awt.event.KeyEvent.KEY_PRESSED;
import static java.awt.event.KeyEvent.KEY_RELEASED;
import static java.awt.event.KeyEvent.VK_0;
import static java.awt.event.KeyEvent.VK_1;
import static java.awt.event.KeyEvent.VK_2;
import static java.awt.event.KeyEvent.VK_3;
import static java.awt.event.KeyEvent.VK_4;
import static java.awt.event.KeyEvent.VK_5;
import static java.awt.event.KeyEvent.VK_6;
import static java.awt.event.KeyEvent.VK_7;
import static java.awt.event.KeyEvent.VK_8;
import static java.awt.event.KeyEvent.VK_9;
import static java.awt.event.KeyEvent.VK_A;
import static java.awt.event.KeyEvent.VK_ALT;
import static java.awt.event.KeyEvent.VK_AMPERSAND;
import static java.awt.event.KeyEvent.VK_ASTERISK;
import static java.awt.event.KeyEvent.VK_AT;
import static java.awt.event.KeyEvent.VK_B;
import static java.awt.event.KeyEvent.VK_BACK_QUOTE;
import static java.awt.event.KeyEvent.VK_BACK_SLASH;
import static java.awt.event.KeyEvent.VK_BACK_SPACE;
import static java.awt.event.KeyEvent.VK_BRACELEFT;
import static java.awt.event.KeyEvent.VK_BRACERIGHT;
import static java.awt.event.KeyEvent.VK_C;
import static java.awt.event.KeyEvent.VK_CAPS_LOCK;
import static java.awt.event.KeyEvent.VK_CIRCUMFLEX;
import static java.awt.event.KeyEvent.VK_CLOSE_BRACKET;
import static java.awt.event.KeyEvent.VK_COLON;
import static java.awt.event.KeyEvent.VK_COMMA;
import static java.awt.event.KeyEvent.VK_CONTROL;
import static java.awt.event.KeyEvent.VK_D;
import static java.awt.event.KeyEvent.VK_DELETE;
import static java.awt.event.KeyEvent.VK_DOLLAR;
import static java.awt.event.KeyEvent.VK_DOWN;
import static java.awt.event.KeyEvent.VK_E;
import static java.awt.event.KeyEvent.VK_END;
import static java.awt.event.KeyEvent.VK_ENTER;
import static java.awt.event.KeyEvent.VK_EQUALS;
import static java.awt.event.KeyEvent.VK_ESCAPE;
import static java.awt.event.KeyEvent.VK_EXCLAMATION_MARK;
import static java.awt.event.KeyEvent.VK_F;
import static java.awt.event.KeyEvent.VK_F1;
import static java.awt.event.KeyEvent.VK_F10;
import static java.awt.event.KeyEvent.VK_F11;
import static java.awt.event.KeyEvent.VK_F12;
import static java.awt.event.KeyEvent.VK_F2;
import static java.awt.event.KeyEvent.VK_F3;
import static java.awt.event.KeyEvent.VK_F4;
import static java.awt.event.KeyEvent.VK_F5;
import static java.awt.event.KeyEvent.VK_F6;
import static java.awt.event.KeyEvent.VK_F7;
import static java.awt.event.KeyEvent.VK_F8;
import static java.awt.event.KeyEvent.VK_F9;
import static java.awt.event.KeyEvent.VK_G;
import static java.awt.event.KeyEvent.VK_GREATER;
import static java.awt.event.KeyEvent.VK_H;
import static java.awt.event.KeyEvent.VK_HOME;
import static java.awt.event.KeyEvent.VK_I;
import static java.awt.event.KeyEvent.VK_INSERT;
import static java.awt.event.KeyEvent.VK_J;
import static java.awt.event.KeyEvent.VK_K;
import static java.awt.event.KeyEvent.VK_L;
import static java.awt.event.KeyEvent.VK_LEFT;
import static java.awt.event.KeyEvent.VK_LEFT_PARENTHESIS;
import static java.awt.event.KeyEvent.VK_LESS;
import static java.awt.event.KeyEvent.VK_M;
import static java.awt.event.KeyEvent.VK_META;
import static java.awt.event.KeyEvent.VK_MINUS;
import static java.awt.event.KeyEvent.VK_N;
import static java.awt.event.KeyEvent.VK_NUMBER_SIGN;
import static java.awt.event.KeyEvent.VK_O;
import static java.awt.event.KeyEvent.VK_OPEN_BRACKET;
import static java.awt.event.KeyEvent.VK_P;
import static java.awt.event.KeyEvent.VK_PAGE_DOWN;
import static java.awt.event.KeyEvent.VK_PAGE_UP;
import static java.awt.event.KeyEvent.VK_PAUSE;
import static java.awt.event.KeyEvent.VK_PERIOD;
import static java.awt.event.KeyEvent.VK_PLUS;
import static java.awt.event.KeyEvent.VK_PRINTSCREEN;
import static java.awt.event.KeyEvent.VK_Q;
import static java.awt.event.KeyEvent.VK_QUOTE;
import static java.awt.event.KeyEvent.VK_QUOTEDBL;
import static java.awt.event.KeyEvent.VK_R;
import static java.awt.event.KeyEvent.VK_RIGHT;
import static java.awt.event.KeyEvent.VK_RIGHT_PARENTHESIS;
import static java.awt.event.KeyEvent.VK_S;
import static java.awt.event.KeyEvent.VK_SEMICOLON;
import static java.awt.event.KeyEvent.VK_SHIFT;
import static java.awt.event.KeyEvent.VK_SLASH;
import static java.awt.event.KeyEvent.VK_SPACE;
import static java.awt.event.KeyEvent.VK_T;
import static java.awt.event.KeyEvent.VK_TAB;
import static java.awt.event.KeyEvent.VK_U;
import static java.awt.event.KeyEvent.VK_UNDERSCORE;
import static java.awt.event.KeyEvent.VK_UP;
import static java.awt.event.KeyEvent.VK_V;
import static java.awt.event.KeyEvent.VK_W;
import static java.awt.event.KeyEvent.VK_WINDOWS;
import static java.awt.event.KeyEvent.VK_X;
import static java.awt.event.KeyEvent.VK_Y;
import static java.awt.event.KeyEvent.VK_Z;

public record KeyAction(Key raw,
                        Key reified,
                        Action action,
                        Modifiers mods) {
    public static KeyAction fromAwt(KeyEvent awt) {
        var raw = Key.fromAwt(awt);
        var shifted = awt.isShiftDown()
                ? raw.shift()
                : raw;
        var reified = Toolkit.getDefaultToolkit().getLockingKeyState(VK_CAPS_LOCK)
                ? shifted.caps()
                : shifted;
        var mods = Modifiers.fromAwt(awt);
        var action = Action.fromAwt(awt);
        return new KeyAction(raw, reified, action, mods);
    }

    public enum Key {
        ESCAPE,
        F1,
        F2,
        F3,
        F4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        F11,
        F12,
        PRINT,
        PAUSE,

        GRAVE('`'),
        ONE('1'),
        TWO('2'),
        THREE('3'),
        FOUR('4'),
        FIVE('5'),
        SIX('6'),
        SEVEN('7'),
        EIGHT('8'),
        NINE('9'),
        ZERO('0'),
        MINUS('-'),
        EQUAL('='),
        BACKSPACE,

        TILDE('~'),
        BANG('!'),
        AT('@'),
        HASH('#'),
        DOLLAR('$'),
        PERCENT('%'),
        CARET('^'),
        AMPERSAND('&'),
        ASTERISK('*'),
        L_PAREN('('),
        R_PAREN(')'),
        UNDERSCORE('_'),
        PLUS('+'),

        INSERT,
        HOME,
        PAGE_UP,

        DELETE,
        END,
        PAGE_DOWN,

        TAB('\t'),
        LOWER_Q('q'),
        LOWER_W('w'),
        LOWER_E('e'),
        LOWER_R('r'),
        LOWER_T('t'),
        LOWER_Y('y'),
        LOWER_U('u'),
        LOWER_I('i'),
        LOWER_O('o'),
        LOWER_P('p'),
        L_BRACKET('['),
        R_BRACKET(']'),
        BACKSLASH('\\'),

        UPPER_Q('Q'),
        UPPER_W('W'),
        UPPER_E('E'),
        UPPER_R('R'),
        UPPER_T('T'),
        UPPER_Y('Y'),
        UPPER_U('U'),
        UPPER_I('I'),
        UPPER_O('O'),
        UPPER_P('P'),
        L_BRACE('{'),
        R_BRACE('}'),
        PIPE('|'),

        CAPS,
        LOWER_A('a'),
        LOWER_S('s'),
        LOWER_D('d'),
        LOWER_F('f'),
        LOWER_G('g'),
        LOWER_H('h'),
        LOWER_J('j'),
        LOWER_K('k'),
        LOWER_L('l'),
        SEMICOLON(';'),
        SINGLE_QUOTE('\''),
        ENTER,

        UPPER_A('A'),
        UPPER_S('S'),
        UPPER_D('D'),
        UPPER_F('F'),
        UPPER_G('G'),
        UPPER_H('H'),
        UPPER_J('J'),
        UPPER_K('K'),
        UPPER_L('L'),
        COLON(':'),
        DOUBLE_QUOTE('"'),

        L_SHIFT,
        LOWER_Z('z'),
        LOWER_X('x'),
        LOWER_C('c'),
        LOWER_V('v'),
        LOWER_B('b'),
        LOWER_N('n'),
        LOWER_M('m'),
        COMMA(','),
        PERIOD('.'),
        FORWARD_SLASH('/'),
        R_SHIFT,

        UPPER_Z('Z'),
        UPPER_X('X'),
        UPPER_C('C'),
        UPPER_V('V'),
        UPPER_B('B'),
        UPPER_N('N'),
        UPPER_M('M'),
        LESS('<'),
        GREATER('>'),
        QUESTION('?'),

        UP,
        LEFT,
        DOWN,
        RIGHT,

        L_CTRL,
        L_WIN,
        L_ALT,
        L_META,
        SPACE(' '),
        R_META,
        R_ALT,
        R_WIN,
        R_CTRL,

        UNKNOWN;

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private final Optional<Character> character;

        Key(Character character) {
            this.character = Optional.ofNullable(character);
        }

        Key() {
            this(null);
        }

        public Optional<Character> character() {
            return character;
        }

        static Key fromAwt(KeyEvent awt) {
            var keyCode = switch (awt.getID()) {
                case KEY_PRESSED, KEY_RELEASED -> awt.getKeyCode();
                default -> throw new UnsupportedOperationException();
            };
            var keyLocation = awt.getKeyLocation();
            return switch (keyCode) {
                case VK_ESCAPE -> Key.ESCAPE;
                case VK_F1 -> Key.F1;
                case VK_F2 -> Key.F2;
                case VK_F3 -> Key.F3;
                case VK_F4 -> Key.F4;
                case VK_F5 -> Key.F5;
                case VK_F6 -> Key.F6;
                case VK_F7 -> Key.F7;
                case VK_F8 -> Key.F8;
                case VK_F9 -> Key.F9;
                case VK_F10 -> Key.F10;
                case VK_F11 -> Key.F11;
                case VK_F12 -> Key.F12;
                case VK_PRINTSCREEN -> Key.PRINT;
                case VK_PAUSE -> Key.PAUSE;
                case VK_BACK_QUOTE -> Key.GRAVE;
                case VK_1 -> Key.ONE;
                case VK_2 -> Key.TWO;
                case VK_3 -> Key.THREE;
                case VK_4 -> Key.FOUR;
                case VK_5 -> Key.FIVE;
                case VK_6 -> Key.SIX;
                case VK_7 -> Key.SEVEN;
                case VK_8 -> Key.EIGHT;
                case VK_9 -> Key.NINE;
                case VK_0 -> Key.ZERO;
                case VK_MINUS -> Key.MINUS;
                case VK_EQUALS -> Key.EQUAL;
                case VK_BACK_SPACE -> Key.BACKSPACE;
                case VK_EXCLAMATION_MARK -> Key.BANG;
                case VK_AT -> Key.AT;
                case VK_NUMBER_SIGN -> Key.HASH;
                case VK_DOLLAR -> Key.DOLLAR;
                case VK_CIRCUMFLEX -> Key.CARET;
                case VK_AMPERSAND -> Key.AMPERSAND;
                case VK_ASTERISK -> Key.ASTERISK;
                case VK_LEFT_PARENTHESIS -> Key.L_PAREN;
                case VK_RIGHT_PARENTHESIS -> Key.R_PAREN;
                case VK_UNDERSCORE -> Key.UNDERSCORE;
                case VK_PLUS -> Key.PLUS;
                case VK_INSERT -> Key.INSERT;
                case VK_HOME -> Key.HOME;
                case VK_PAGE_UP -> Key.PAGE_UP;
                case VK_DELETE -> Key.DELETE;
                case VK_END -> Key.END;
                case VK_PAGE_DOWN -> Key.PAGE_DOWN;
                case VK_TAB -> Key.TAB;
                case VK_Q -> Key.LOWER_Q;
                case VK_W -> Key.LOWER_W;
                case VK_E -> Key.LOWER_E;
                case VK_R -> Key.LOWER_R;
                case VK_T -> Key.LOWER_T;
                case VK_Y -> Key.LOWER_Y;
                case VK_U -> Key.LOWER_U;
                case VK_I -> Key.LOWER_I;
                case VK_O -> Key.LOWER_O;
                case VK_P -> Key.LOWER_P;
                case VK_OPEN_BRACKET -> Key.L_BRACKET;
                case VK_CLOSE_BRACKET -> Key.R_BRACKET;
                case VK_BACK_SLASH -> Key.BACKSLASH;
                case VK_BRACELEFT -> Key.L_BRACE;
                case VK_BRACERIGHT -> Key.R_BRACE;
                case VK_CAPS_LOCK -> Key.CAPS;
                case VK_A -> Key.LOWER_A;
                case VK_S -> Key.LOWER_S;
                case VK_D -> Key.LOWER_D;
                case VK_F -> Key.LOWER_F;
                case VK_G -> Key.LOWER_G;
                case VK_H -> Key.LOWER_H;
                case VK_J -> Key.LOWER_J;
                case VK_K -> Key.LOWER_K;
                case VK_L -> Key.LOWER_L;
                case VK_SEMICOLON -> Key.SEMICOLON;
                case VK_QUOTE -> Key.SINGLE_QUOTE;
                case VK_ENTER -> Key.ENTER;
                case VK_COLON -> Key.COLON;
                case VK_QUOTEDBL -> Key.DOUBLE_QUOTE;
                case VK_SHIFT -> switch (keyLocation) {
                    case KEY_LOCATION_STANDARD, KEY_LOCATION_LEFT -> Key.L_SHIFT;
                    case KEY_LOCATION_RIGHT -> Key.R_SHIFT;
                    default -> Key.UNKNOWN;
                };
                case VK_Z -> Key.LOWER_Z;
                case VK_X -> Key.LOWER_X;
                case VK_C -> Key.LOWER_C;
                case VK_V -> Key.LOWER_V;
                case VK_B -> Key.LOWER_B;
                case VK_N -> Key.LOWER_N;
                case VK_M -> Key.LOWER_M;
                case VK_COMMA -> Key.COMMA;
                case VK_PERIOD -> Key.PERIOD;
                case VK_SLASH -> Key.FORWARD_SLASH;
                case VK_LESS -> Key.LESS;
                case VK_GREATER -> Key.GREATER;
                case VK_UP -> Key.UP;
                case VK_LEFT -> Key.LEFT;
                case VK_DOWN -> Key.DOWN;
                case VK_RIGHT -> Key.RIGHT;
                case VK_CONTROL -> switch (keyLocation) {
                    case KEY_LOCATION_LEFT -> Key.L_CTRL;
                    case KEY_LOCATION_RIGHT -> Key.R_CTRL;
                    default -> Key.UNKNOWN;
                };
                case VK_WINDOWS -> switch (keyLocation) {
                    case KEY_LOCATION_LEFT -> Key.L_WIN;
                    case KEY_LOCATION_RIGHT -> Key.R_WIN;
                    default -> Key.UNKNOWN;
                };
                case VK_ALT -> switch (keyLocation) {
                    case KEY_LOCATION_LEFT -> Key.L_ALT;
                    case KEY_LOCATION_RIGHT -> Key.R_ALT;
                    default -> Key.UNKNOWN;
                };
                case VK_META -> switch (keyLocation) {
                    case KEY_LOCATION_LEFT -> Key.L_META;
                    case KEY_LOCATION_RIGHT -> Key.R_META;
                    default -> Key.UNKNOWN;
                };
                case VK_SPACE -> Key.SPACE;
                default -> Key.UNKNOWN;
            };
        }

        Key shift() {
            return switch (this) {
                case Key.GRAVE -> Key.TILDE;
                case Key.ONE -> Key.BANG;
                case Key.TWO -> Key.AT;
                case Key.THREE -> Key.HASH;
                case Key.FOUR -> Key.DOLLAR;
                case Key.FIVE -> Key.PERCENT;
                case Key.SIX -> Key.CARET;
                case Key.SEVEN -> Key.AMPERSAND;
                case Key.EIGHT -> Key.ASTERISK;
                case Key.NINE -> Key.L_PAREN;
                case Key.ZERO -> Key.R_PAREN;
                case Key.MINUS -> Key.UNDERSCORE;
                case Key.EQUAL -> Key.PLUS;
                case Key.LOWER_Q -> Key.UPPER_Q;
                case Key.LOWER_W -> Key.UPPER_W;
                case Key.LOWER_E -> Key.UPPER_E;
                case Key.LOWER_R -> Key.UPPER_R;
                case Key.LOWER_T -> Key.UPPER_T;
                case Key.LOWER_Y -> Key.UPPER_Y;
                case Key.LOWER_U -> Key.UPPER_U;
                case Key.LOWER_I -> Key.UPPER_I;
                case Key.LOWER_O -> Key.UPPER_O;
                case Key.LOWER_P -> Key.UPPER_P;
                case Key.L_BRACKET -> Key.L_BRACE;
                case Key.R_BRACKET -> Key.R_BRACE;
                case Key.BACKSLASH -> Key.PIPE;
                case Key.LOWER_A -> Key.UPPER_A;
                case Key.LOWER_S -> Key.UPPER_S;
                case Key.LOWER_D -> Key.UPPER_D;
                case Key.LOWER_F -> Key.UPPER_F;
                case Key.LOWER_G -> Key.UPPER_G;
                case Key.LOWER_H -> Key.UPPER_H;
                case Key.LOWER_J -> Key.UPPER_J;
                case Key.LOWER_K -> Key.UPPER_K;
                case Key.LOWER_L -> Key.UPPER_L;
                case Key.SEMICOLON -> Key.COLON;
                case Key.SINGLE_QUOTE -> Key.DOUBLE_QUOTE;
                case Key.LOWER_Z -> Key.UPPER_Z;
                case Key.LOWER_X -> Key.UPPER_X;
                case Key.LOWER_C -> Key.UPPER_C;
                case Key.LOWER_V -> Key.UPPER_V;
                case Key.LOWER_B -> Key.UPPER_B;
                case Key.LOWER_N -> Key.UPPER_N;
                case Key.LOWER_M -> Key.UPPER_M;
                case Key.COMMA -> Key.LESS;
                case Key.PERIOD -> Key.GREATER;
                case Key.FORWARD_SLASH -> Key.QUESTION;
                default -> this;
            };
        }

        Key caps() {
            return switch (this) {
                case Key.LOWER_Q -> Key.UPPER_Q;
                case Key.LOWER_W -> Key.UPPER_W;
                case Key.LOWER_E -> Key.UPPER_E;
                case Key.LOWER_R -> Key.UPPER_R;
                case Key.LOWER_T -> Key.UPPER_T;
                case Key.LOWER_Y -> Key.UPPER_Y;
                case Key.LOWER_U -> Key.UPPER_U;
                case Key.LOWER_I -> Key.UPPER_I;
                case Key.LOWER_O -> Key.UPPER_O;
                case Key.LOWER_P -> Key.UPPER_P;
                case Key.LOWER_A -> Key.UPPER_A;
                case Key.LOWER_S -> Key.UPPER_S;
                case Key.LOWER_D -> Key.UPPER_D;
                case Key.LOWER_F -> Key.UPPER_F;
                case Key.LOWER_G -> Key.UPPER_G;
                case Key.LOWER_H -> Key.UPPER_H;
                case Key.LOWER_J -> Key.UPPER_J;
                case Key.LOWER_K -> Key.UPPER_K;
                case Key.LOWER_L -> Key.UPPER_L;
                case Key.LOWER_Z -> Key.UPPER_Z;
                case Key.LOWER_X -> Key.UPPER_X;
                case Key.LOWER_C -> Key.UPPER_C;
                case Key.LOWER_V -> Key.UPPER_V;
                case Key.LOWER_B -> Key.UPPER_B;
                case Key.LOWER_N -> Key.UPPER_N;
                case Key.LOWER_M -> Key.UPPER_M;
                case Key.UPPER_Q -> Key.LOWER_Q;
                case Key.UPPER_W -> Key.LOWER_W;
                case Key.UPPER_E -> Key.LOWER_E;
                case Key.UPPER_R -> Key.LOWER_R;
                case Key.UPPER_T -> Key.LOWER_T;
                case Key.UPPER_Y -> Key.LOWER_Y;
                case Key.UPPER_U -> Key.LOWER_U;
                case Key.UPPER_I -> Key.LOWER_I;
                case Key.UPPER_O -> Key.LOWER_O;
                case Key.UPPER_P -> Key.LOWER_P;
                case Key.UPPER_A -> Key.LOWER_A;
                case Key.UPPER_S -> Key.LOWER_S;
                case Key.UPPER_D -> Key.LOWER_D;
                case Key.UPPER_F -> Key.LOWER_F;
                case Key.UPPER_G -> Key.LOWER_G;
                case Key.UPPER_H -> Key.LOWER_H;
                case Key.UPPER_J -> Key.LOWER_J;
                case Key.UPPER_K -> Key.LOWER_K;
                case Key.UPPER_L -> Key.LOWER_L;
                case Key.UPPER_Z -> Key.LOWER_Z;
                case Key.UPPER_X -> Key.LOWER_X;
                case Key.UPPER_C -> Key.LOWER_C;
                case Key.UPPER_V -> Key.LOWER_V;
                case Key.UPPER_B -> Key.LOWER_B;
                case Key.UPPER_N -> Key.LOWER_N;
                case Key.UPPER_M -> Key.LOWER_M;
                default -> this;
            };
        }
    }

    public enum Action {
        PRESS,
        RELEASE;

        static Action fromAwt(KeyEvent awt) {
            return switch (awt.getID()) {
                case KEY_PRESSED -> Action.PRESS;
                case KEY_RELEASED -> Action.RELEASE;
                default -> throw new UnsupportedOperationException();
            };
        }
    }

    public record Modifiers(boolean lCtrl,
                            boolean rCtrl,
                            boolean lAlt,
                            boolean rAlt,
                            boolean lShift,
                            boolean rShift,
                            boolean lMeta,
                            boolean rMeta) {
        public boolean ctrl() {
            return lCtrl || rCtrl;
        }

        public boolean alt() {
            return lAlt || rAlt;
        }

        public boolean shift() {
            return lShift || rShift;
        }

        public boolean meta() {
            return lMeta || rMeta;
        }

        public boolean any() {
            return ctrl() || alt() || shift() || meta();
        }

        public boolean none() {
            return !any();
        }

        static Modifiers fromAwt(KeyEvent awt) {
            boolean lCtrl = false, rCtrl = false;
            if (awt.isControlDown()) {
                switch (awt.getKeyLocation()) {
                    case KEY_LOCATION_LEFT -> lCtrl = true;
                    case KEY_LOCATION_RIGHT -> rCtrl = true;
                    default -> throw new UnsupportedOperationException("" + awt.getKeyLocation());
                }
            }
            boolean lAlt = false, rAlt = false;
            if (awt.isAltDown()) {
                switch (awt.getKeyLocation()) {
                    case KEY_LOCATION_LEFT -> lAlt = true;
                    case KEY_LOCATION_RIGHT -> rAlt = true;
                    default -> throw new UnsupportedOperationException("" + awt.getKeyLocation());
                }
            }
            if (awt.isAltGraphDown()) {
                rAlt = true;
            }
            boolean lShift = false, rShift = false;
            if (awt.isShiftDown()) {
                switch (awt.getKeyLocation()) {
                    case KEY_LOCATION_LEFT -> lShift = true;
                    case KEY_LOCATION_RIGHT -> rShift = true;
                    default -> throw new UnsupportedOperationException("" + awt.getKeyLocation());
                }
            }
            boolean lMeta = false, rMeta = false;
            if (awt.isMetaDown()) {
                switch (awt.getKeyLocation()) {
                    case KEY_LOCATION_LEFT -> lMeta = true;
                    case KEY_LOCATION_RIGHT -> rMeta = true;
                    default -> throw new UnsupportedOperationException("" + awt.getKeyLocation());
                }
            }
            return new Modifiers(lCtrl, rCtrl, lAlt, rAlt, lShift, rShift, lMeta, rMeta);
        }
    }
}
