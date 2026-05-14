package ui;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;

public class KeyChordParseTest {
    @Test
    void singleLetter() {
        assertEquals(KeyChord.of(KeyAction.Key.LOWER_Q), KeyChord.parse("q"));
    }

    @Test
    void ctrlPlusLetter() {
        assertEquals(KeyChord.ctrl(KeyAction.Key.LOWER_A), KeyChord.parse("ctrl+a"));
    }

    @Test
    void ctrlShiftPlusLetter() {
        assertEquals(KeyChord.ctrlShift(KeyAction.Key.LOWER_Z), KeyChord.parse("ctrl+shift+z"));
    }

    @Test
    void modifierOrderIndependent() {
        assertEquals(KeyChord.parse("ctrl+shift+z"), KeyChord.parse("shift+ctrl+z"));
    }

    @Test
    void modifiersCaseInsensitive() {
        assertEquals(KeyChord.parse("ctrl+shift+z"), KeyChord.parse("CTRL+Shift+Z"));
    }

    @Test
    void functionKey() {
        assertEquals(KeyChord.of(KeyAction.Key.F5), KeyChord.parse("f5"));
    }

    @Test
    void namedKey() {
        assertEquals(KeyChord.of(KeyAction.Key.ESCAPE), KeyChord.parse("escape"));
    }

    @Test
    void symbolKey() {
        assertEquals(KeyChord.of(KeyAction.Key.FORWARD_SLASH), KeyChord.parse("/"));
    }

    @Test
    void plusKey() {
        assertEquals(KeyChord.of(KeyAction.Key.PLUS), KeyChord.parse("+"));
    }

    @Test
    void ctrlPlusPlusKey() {
        assertEquals(KeyChord.ctrl(KeyAction.Key.PLUS), KeyChord.parse("ctrl++"));
    }

    @Test
    void altAndMeta() {
        var chord = KeyChord.parse("alt+meta+q");
        assertEquals(KeyAction.Key.LOWER_Q, chord.key());
        assertEquals(true, chord.alt());
        assertEquals(true, chord.meta());
        assertEquals(false, chord.ctrl());
        assertEquals(false, chord.shift());
    }

    @Test
    void unknownKeyThrows() {
        try {
            KeyChord.parse("ctrl+nope");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void unknownModifierThrows() {
        try {
            KeyChord.parse("hyper+z");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void emptyThrows() {
        try {
            KeyChord.parse("");
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
