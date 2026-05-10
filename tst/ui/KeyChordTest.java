package ui;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertNotEquals;

public class KeyChordTest {
    @Test
    void factoryOfHasNoModifiers() {
        var k = KeyChord.of(KeyAction.Key.LOWER_W);
        assertEquals(KeyAction.Key.LOWER_W, k.key());
        assertEquals(false, k.ctrl());
        assertEquals(false, k.shift());
        assertEquals(false, k.alt());
        assertEquals(false, k.meta());
    }

    @Test
    void ctrlFactoryHasCtrl() {
        var k = KeyChord.ctrl(KeyAction.Key.LOWER_C);
        assertEquals(true, k.ctrl());
        assertEquals(false, k.shift());
    }

    @Test
    void shiftFactoryHasShift() {
        var k = KeyChord.shift(KeyAction.Key.LOWER_W);
        assertEquals(false, k.ctrl());
        assertEquals(true, k.shift());
    }

    @Test
    void plainAndShiftedDifferent() {
        assertNotEquals(KeyChord.of(KeyAction.Key.LOWER_W), KeyChord.shift(KeyAction.Key.LOWER_W));
    }

    @Test
    void equalChordsEqual() {
        assertEquals(KeyChord.of(KeyAction.Key.LOWER_A), KeyChord.of(KeyAction.Key.LOWER_A));
    }

    @Test
    void fromActionPicksUpModifiers() {
        var mods = new KeyAction.Modifiers(true, false, false, false, true, false, false, false);
        var action = new KeyAction(KeyAction.Key.LOWER_W, KeyAction.Key.LOWER_W, KeyAction.Action.PRESS, mods);
        var chord = KeyChord.from(action);
        assertEquals(KeyAction.Key.LOWER_W, chord.key());
        assertEquals(true, chord.ctrl());
        assertEquals(true, chord.shift());
        assertEquals(false, chord.alt());
        assertEquals(false, chord.meta());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
