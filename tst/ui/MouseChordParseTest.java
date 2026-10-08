package ui;

import static harness.Assertions.assertEquals;

import harness.SuiteRunner;
import harness.Test;

public class MouseChordParseTest {
    @Test
    void gestureOnly() {
        assertEquals(
                MouseChord.of(MouseButton.NONE, MouseGesture.PRESS, ""), MouseChord.parse("press"));
    }

    @Test
    void buttonAndGesture() {
        assertEquals(
                MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, ""),
                MouseChord.parse("left+press"));
    }

    @Test
    void buttonGestureMode() {
        assertEquals(
                MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, "brush"),
                MouseChord.parse("left+press+brush"));
    }

    @Test
    void gestureAndMode() {
        assertEquals(
                MouseChord.of(MouseButton.NONE, MouseGesture.DRAG, "box_select"),
                MouseChord.parse("drag+box_select"));
    }

    @Test
    void wheelGesture() {
        assertEquals(
                MouseChord.of(MouseButton.NONE, MouseGesture.WHEEL, "console"),
                MouseChord.parse("wheel+console"));
    }

    @Test
    void modifiers() {
        var chord = MouseChord.parse("ctrl+shift+right+press");
        assertEquals(MouseButton.RIGHT, chord.button());
        assertEquals(MouseGesture.PRESS, chord.gesture());
        assertEquals(true, chord.ctrl());
        assertEquals(true, chord.shift());
        assertEquals(false, chord.alt());
        assertEquals(false, chord.meta());
    }

    @Test
    void orderIndependent() {
        assertEquals(MouseChord.parse("left+press+brush"), MouseChord.parse("brush+press+left"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(MouseChord.parse("left+press+brush"), MouseChord.parse("LEFT+Press+Brush"));
    }

    @Test
    void modeNormalisedToLowerCase() {
        assertEquals("brush", MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, "BRUSH").mode());
    }

    @Test
    void noGestureThrows() {
        try {
            MouseChord.parse("left+brush");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void multipleModesThrows() {
        try {
            MouseChord.parse("press+brush+fill");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void emptyThrows() {
        try {
            MouseChord.parse("");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
