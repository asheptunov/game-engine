package platform.awt.input;

import engine.input.KeyCode;
import engine.input.KeyInput;
import engine.input.Modifiers;
import engine.input.MouseButton;
import engine.input.MouseGesture;
import engine.input.MouseInput;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/** Converts AWT events to normalized engine input without consulting lock-key state. */
public final class AwtInputAdapter {
    private AwtInputAdapter() {}

    public static KeyInput key(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED && event.getID() != KeyEvent.KEY_RELEASED)
            throw new IllegalArgumentException("Expected a key press or release event");
        return new KeyInput(
                keyCode(event), event.getID() == KeyEvent.KEY_PRESSED, modifiers(event));
    }

    public static MouseInput mouse(MouseGesture gesture, MouseEvent event, String mode) {
        double wheel = event instanceof MouseWheelEvent value ? value.getPreciseWheelRotation() : 0;
        return new MouseInput(
                button(gesture, event),
                gesture,
                mode,
                modifiers(event),
                event.getX(),
                event.getY(),
                wheel);
    }

    public static Modifiers modifiers(InputEvent event) {
        int mask = event.getModifiersEx();
        return new Modifiers(
                (mask & InputEvent.CTRL_DOWN_MASK) != 0,
                (mask & InputEvent.ALT_DOWN_MASK) != 0,
                (mask & InputEvent.SHIFT_DOWN_MASK) != 0,
                (mask & InputEvent.META_DOWN_MASK) != 0);
    }

    private static MouseButton button(MouseGesture gesture, MouseEvent event) {
        if (gesture == MouseGesture.PRESS || gesture == MouseGesture.RELEASE) {
            var direct = directButton(event.getButton());
            if (direct != MouseButton.NONE) return direct;
        }
        int mask = event.getModifiersEx(), count = 0;
        MouseButton result = MouseButton.NONE;
        if ((mask & InputEvent.BUTTON1_DOWN_MASK) != 0) {
            count++;
            result = MouseButton.LEFT;
        }
        if ((mask & InputEvent.BUTTON2_DOWN_MASK) != 0) {
            count++;
            result = MouseButton.MIDDLE;
        }
        if ((mask & InputEvent.BUTTON3_DOWN_MASK) != 0) {
            count++;
            result = MouseButton.RIGHT;
        }
        if (count > 1)
            throw new IllegalArgumentException("Ambiguous mouse event has multiple held buttons");
        return result;
    }

    private static MouseButton directButton(int button) {
        return switch (button) {
            case MouseEvent.BUTTON1 -> MouseButton.LEFT;
            case MouseEvent.BUTTON2 -> MouseButton.MIDDLE;
            case MouseEvent.BUTTON3 -> MouseButton.RIGHT;
            default -> MouseButton.NONE;
        };
    }

    public static KeyCode keyCode(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> KeyCode.ESCAPE;
            case KeyEvent.VK_F1 -> KeyCode.F1;
            case KeyEvent.VK_F2 -> KeyCode.F2;
            case KeyEvent.VK_F3 -> KeyCode.F3;
            case KeyEvent.VK_F4 -> KeyCode.F4;
            case KeyEvent.VK_F5 -> KeyCode.F5;
            case KeyEvent.VK_F6 -> KeyCode.F6;
            case KeyEvent.VK_F7 -> KeyCode.F7;
            case KeyEvent.VK_F8 -> KeyCode.F8;
            case KeyEvent.VK_F9 -> KeyCode.F9;
            case KeyEvent.VK_F10 -> KeyCode.F10;
            case KeyEvent.VK_F11 -> KeyCode.F11;
            case KeyEvent.VK_F12 -> KeyCode.F12;
            case KeyEvent.VK_PRINTSCREEN -> KeyCode.PRINT;
            case KeyEvent.VK_PAUSE -> KeyCode.PAUSE;
            case KeyEvent.VK_BACK_QUOTE -> KeyCode.GRAVE;
            case KeyEvent.VK_1 -> KeyCode.ONE;
            case KeyEvent.VK_2 -> KeyCode.TWO;
            case KeyEvent.VK_3 -> KeyCode.THREE;
            case KeyEvent.VK_4 -> KeyCode.FOUR;
            case KeyEvent.VK_5 -> KeyCode.FIVE;
            case KeyEvent.VK_6 -> KeyCode.SIX;
            case KeyEvent.VK_7 -> KeyCode.SEVEN;
            case KeyEvent.VK_8 -> KeyCode.EIGHT;
            case KeyEvent.VK_9 -> KeyCode.NINE;
            case KeyEvent.VK_0 -> KeyCode.ZERO;
            case KeyEvent.VK_MINUS -> KeyCode.MINUS;
            case KeyEvent.VK_EQUALS -> KeyCode.EQUAL;
            case KeyEvent.VK_EXCLAMATION_MARK -> KeyCode.ONE;
            case KeyEvent.VK_AT -> KeyCode.TWO;
            case KeyEvent.VK_NUMBER_SIGN -> KeyCode.THREE;
            case KeyEvent.VK_DOLLAR -> KeyCode.FOUR;
            case KeyEvent.VK_CIRCUMFLEX -> KeyCode.SIX;
            case KeyEvent.VK_AMPERSAND -> KeyCode.SEVEN;
            case KeyEvent.VK_ASTERISK -> KeyCode.EIGHT;
            case KeyEvent.VK_LEFT_PARENTHESIS -> KeyCode.NINE;
            case KeyEvent.VK_RIGHT_PARENTHESIS -> KeyCode.ZERO;
            case KeyEvent.VK_UNDERSCORE -> KeyCode.MINUS;
            case KeyEvent.VK_PLUS -> KeyCode.EQUAL;
            case KeyEvent.VK_BACK_SPACE -> KeyCode.BACKSPACE;
            case KeyEvent.VK_INSERT -> KeyCode.INSERT;
            case KeyEvent.VK_HOME -> KeyCode.HOME;
            case KeyEvent.VK_PAGE_UP -> KeyCode.PAGE_UP;
            case KeyEvent.VK_DELETE -> KeyCode.DELETE;
            case KeyEvent.VK_END -> KeyCode.END;
            case KeyEvent.VK_PAGE_DOWN -> KeyCode.PAGE_DOWN;
            case KeyEvent.VK_TAB -> KeyCode.TAB;
            case KeyEvent.VK_Q -> KeyCode.Q;
            case KeyEvent.VK_W -> KeyCode.W;
            case KeyEvent.VK_E -> KeyCode.E;
            case KeyEvent.VK_R -> KeyCode.R;
            case KeyEvent.VK_T -> KeyCode.T;
            case KeyEvent.VK_Y -> KeyCode.Y;
            case KeyEvent.VK_U -> KeyCode.U;
            case KeyEvent.VK_I -> KeyCode.I;
            case KeyEvent.VK_O -> KeyCode.O;
            case KeyEvent.VK_P -> KeyCode.P;
            case KeyEvent.VK_OPEN_BRACKET -> KeyCode.L_BRACKET;
            case KeyEvent.VK_CLOSE_BRACKET -> KeyCode.R_BRACKET;
            case KeyEvent.VK_BACK_SLASH -> KeyCode.BACKSLASH;
            case KeyEvent.VK_CAPS_LOCK -> KeyCode.CAPS;
            case KeyEvent.VK_BRACELEFT -> KeyCode.L_BRACKET;
            case KeyEvent.VK_BRACERIGHT -> KeyCode.R_BRACKET;
            case KeyEvent.VK_A -> KeyCode.A;
            case KeyEvent.VK_S -> KeyCode.S;
            case KeyEvent.VK_D -> KeyCode.D;
            case KeyEvent.VK_F -> KeyCode.F;
            case KeyEvent.VK_G -> KeyCode.G;
            case KeyEvent.VK_H -> KeyCode.H;
            case KeyEvent.VK_J -> KeyCode.J;
            case KeyEvent.VK_K -> KeyCode.K;
            case KeyEvent.VK_L -> KeyCode.L;
            case KeyEvent.VK_SEMICOLON -> KeyCode.SEMICOLON;
            case KeyEvent.VK_QUOTE -> KeyCode.SINGLE_QUOTE;
            case KeyEvent.VK_COLON -> KeyCode.SEMICOLON;
            case KeyEvent.VK_QUOTEDBL -> KeyCode.SINGLE_QUOTE;
            case KeyEvent.VK_ENTER -> KeyCode.ENTER;
            case KeyEvent.VK_Z -> KeyCode.Z;
            case KeyEvent.VK_X -> KeyCode.X;
            case KeyEvent.VK_C -> KeyCode.C;
            case KeyEvent.VK_V -> KeyCode.V;
            case KeyEvent.VK_B -> KeyCode.B;
            case KeyEvent.VK_N -> KeyCode.N;
            case KeyEvent.VK_M -> KeyCode.M;
            case KeyEvent.VK_COMMA -> KeyCode.COMMA;
            case KeyEvent.VK_PERIOD -> KeyCode.PERIOD;
            case KeyEvent.VK_LESS -> KeyCode.COMMA;
            case KeyEvent.VK_GREATER -> KeyCode.PERIOD;
            case KeyEvent.VK_SLASH -> KeyCode.FORWARD_SLASH;
            case KeyEvent.VK_UP -> KeyCode.UP;
            case KeyEvent.VK_LEFT -> KeyCode.LEFT;
            case KeyEvent.VK_DOWN -> KeyCode.DOWN;
            case KeyEvent.VK_RIGHT -> KeyCode.RIGHT;
            case KeyEvent.VK_SPACE -> KeyCode.SPACE;
            case KeyEvent.VK_SHIFT -> side(event, KeyCode.L_SHIFT, KeyCode.R_SHIFT);
            case KeyEvent.VK_CONTROL -> side(event, KeyCode.L_CTRL, KeyCode.R_CTRL);
            case KeyEvent.VK_ALT -> side(event, KeyCode.L_ALT, KeyCode.R_ALT);
            case KeyEvent.VK_META -> side(event, KeyCode.L_META, KeyCode.R_META);
            case KeyEvent.VK_WINDOWS -> side(event, KeyCode.L_WIN, KeyCode.R_WIN);
            default -> KeyCode.UNKNOWN;
        };
    }

    private static KeyCode side(KeyEvent event, KeyCode left, KeyCode right) {
        return event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT ? right : left;
    }
}
