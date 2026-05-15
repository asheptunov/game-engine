package ui;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

public class InputBindingsTest {
    private static KeyAction keyOnly(KeyAction.Key key) {
        var noMods = new KeyAction.Modifiers(false, false, false, false, false, false, false, false);
        return new KeyAction(key, key, KeyAction.Action.PRESS, noMods);
    }

    @Test
    void handleFiresBoundAction() {
        var r = new ActionRegistry<Runnable>();
        var fired = new boolean[]{false};
        r.register("act", () -> fired[0] = true);
        var b = new InputBindings(r).bind(KeyChord.of(KeyAction.Key.LOWER_W), "act");
        assertTrue(b.handle(keyOnly(KeyAction.Key.LOWER_W)));
        assertTrue(fired[0]);
    }

    @Test
    void handleReturnsFalseWhenUnbound() {
        var b = new InputBindings(new ActionRegistry<Runnable>());
        assertFalse(b.handle(keyOnly(KeyAction.Key.LOWER_W)));
    }

    @Test
    void handleReturnsFalseWhenBoundButUnregistered() {
        var b = new InputBindings(new ActionRegistry<Runnable>())
                .bind(KeyChord.of(KeyAction.Key.LOWER_W), "ghost");
        assertFalse(b.handle(keyOnly(KeyAction.Key.LOWER_W)));
    }

    @Test
    void unresolvedListsMissingActionIds() {
        var r = new ActionRegistry<Runnable>().register("present", () -> {});
        var b = new InputBindings(r)
                .bind(KeyChord.of(KeyAction.Key.LOWER_A), "present")
                .bind(KeyChord.of(KeyAction.Key.LOWER_B), "missing1")
                .bind(KeyChord.of(KeyAction.Key.LOWER_C), "missing2");
        var u = b.unresolved();
        assertEquals(2, u.size());
        assertTrue(u.containsValue("missing1"));
        assertTrue(u.containsValue("missing2"));
    }

    @Test
    void lookupReturnsActionId() {
        var b = new InputBindings(new ActionRegistry<Runnable>())
                .bind(KeyChord.of(KeyAction.Key.LOWER_W), "fwd");
        assertEquals("fwd", b.lookup(KeyChord.of(KeyAction.Key.LOWER_W)).orElseThrow());
    }

    @Test
    void shiftedAndPlainKeyAreDistinct() {
        var r = new ActionRegistry<Runnable>()
                .register("plain", () -> {})
                .register("shifted", () -> {});
        var b = new InputBindings(r)
                .bind(KeyChord.of(KeyAction.Key.LOWER_W), "plain")
                .bind(KeyChord.shift(KeyAction.Key.LOWER_W), "shifted");
        var shifted = new KeyAction.Modifiers(false, false, false, false, true, false, false, false);
        var shiftedAction = new KeyAction(KeyAction.Key.LOWER_W, KeyAction.Key.UPPER_W,
                KeyAction.Action.PRESS, shifted);
        assertEquals("shifted", b.lookup(KeyChord.from(shiftedAction)).orElseThrow());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
