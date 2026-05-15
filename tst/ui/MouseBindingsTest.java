package ui;

import harness.SuiteRunner;
import harness.Test;

import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertFalse;
import static harness.Assertions.assertTrue;

public class MouseBindingsTest {
    private static final Canvas SRC = new Canvas();

    private static MouseEvent press(int button) {
        return new MouseEvent(SRC, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                0, 0, 1, false, button);
    }

    @Test
    void handleFiresBoundActionWithEvent() {
        var seen = new MouseEvent[1];
        var actions = new ActionRegistry<Consumer<MouseEvent>>().register("act", e -> seen[0] = e);
        var b = new MouseBindings(actions)
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, ""), "act");
        var event = press(MouseEvent.BUTTON1);
        assertTrue(b.handle(MouseGesture.PRESS, event, ""));
        assertEquals(event, seen[0]);
    }

    @Test
    void handleReturnsFalseWhenUnbound() {
        var b = new MouseBindings(new ActionRegistry<>());
        assertFalse(b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON1), ""));
    }

    @Test
    void handleReturnsFalseWhenBoundButUnregistered() {
        var b = new MouseBindings(new ActionRegistry<Consumer<MouseEvent>>())
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, ""), "ghost");
        assertFalse(b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON1), ""));
    }

    @Test
    void modeDistinguishesBindings() {
        var fired = new String[1];
        var actions = new ActionRegistry<Consumer<MouseEvent>>()
                .register("a1", e -> fired[0] = "a1")
                .register("a2", e -> fired[0] = "a2");
        var b = new MouseBindings(actions)
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, "brush"), "a1")
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, "fill"), "a2");
        b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON1), "BRUSH");
        assertEquals("a1", fired[0]);
        b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON1), "FILL");
        assertEquals("a2", fired[0]);
    }

    @Test
    void buttonDistinguishesBindings() {
        var actions = new ActionRegistry<Consumer<MouseEvent>>().register("left", e -> {});
        var b = new MouseBindings(actions)
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, ""), "left");
        assertTrue(b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON1), ""));
        assertFalse(b.handle(MouseGesture.PRESS, press(MouseEvent.BUTTON3), ""));
    }

    @Test
    void unresolvedListsMissingActionIds() {
        var actions = new ActionRegistry<Consumer<MouseEvent>>().register("present", e -> {});
        var b = new MouseBindings(actions)
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, ""), "present")
                .bind(MouseChord.of(MouseButton.LEFT, MouseGesture.DRAG, ""), "missing");
        var u = b.unresolved();
        assertEquals(1, u.size());
        assertTrue(u.containsValue("missing"));
    }

    @Test
    void lookupReturnsActionId() {
        var b = new MouseBindings(new ActionRegistry<>())
                .bind(MouseChord.of(MouseButton.NONE, MouseGesture.WHEEL, "console"), "scroll");
        assertEquals("scroll",
                b.lookup(MouseChord.of(MouseButton.NONE, MouseGesture.WHEEL, "console")).orElseThrow());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
