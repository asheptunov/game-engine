package platform.awt.input;

import static harness.Assertions.*;

import engine.input.*;

import harness.SuiteRunner;
import harness.Test;

import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

public class AwtInputAdapterTest {
    private static final Canvas SOURCE = new Canvas();

    @Test
    void keyUsesPhysicalCodeAndExactModifiersWithoutCharacterOrLockState() {
        var lower =
                new KeyEvent(
                        SOURCE,
                        KeyEvent.KEY_PRESSED,
                        1,
                        InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_Z,
                        'z',
                        KeyEvent.KEY_LOCATION_STANDARD);
        var upper =
                new KeyEvent(
                        SOURCE,
                        KeyEvent.KEY_PRESSED,
                        1,
                        InputEvent.CTRL_DOWN_MASK,
                        KeyEvent.VK_Z,
                        'Z',
                        KeyEvent.KEY_LOCATION_STANDARD);
        assertEquals(
                new KeyInput(KeyCode.Z, true, new Modifiers(true, false, false, false)),
                AwtInputAdapter.key(lower));
        assertEquals(AwtInputAdapter.key(lower), AwtInputAdapter.key(upper));
    }

    @Test
    void dragInfersHeldRightButtonAndKeepsShiftDistinct() {
        var right = drag(InputEvent.BUTTON3_DOWN_MASK);
        var shifted = drag(InputEvent.BUTTON3_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(
                MouseButton.RIGHT,
                AwtInputAdapter.mouse(MouseGesture.DRAG, right, "viewport").button());
        assertEquals(
                Modifiers.NONE,
                AwtInputAdapter.mouse(MouseGesture.DRAG, right, "viewport").modifiers());
        assertEquals(
                new Modifiers(false, false, true, false),
                AwtInputAdapter.mouse(MouseGesture.DRAG, shifted, "viewport").modifiers());
    }

    @Test
    void multipleHeldButtonsAreRejected() {
        try {
            AwtInputAdapter.mouse(
                    MouseGesture.DRAG,
                    drag(InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON3_DOWN_MASK),
                    "viewport");
            throw new RuntimeException("Expected ambiguous held buttons to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("multiple held buttons"));
        }
    }

    private static MouseEvent drag(int modifiers) {
        return new MouseEvent(
                SOURCE,
                MouseEvent.MOUSE_DRAGGED,
                1,
                modifiers,
                7,
                9,
                0,
                false,
                MouseEvent.NOBUTTON);
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
