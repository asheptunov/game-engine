package ui;

import java.awt.event.MouseEvent;

/**
 * Mouse button identity for {@link MouseChord}. {@code NONE} covers buttonless events — AWT reports
 * no button for drag and wheel events.
 */
public enum MouseButton {
    LEFT,
    MIDDLE,
    RIGHT,
    NONE;

    static MouseButton fromAwt(MouseEvent e) {
        return switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> LEFT;
            case MouseEvent.BUTTON2 -> MIDDLE;
            case MouseEvent.BUTTON3 -> RIGHT;
            default -> NONE;
        };
    }
}
