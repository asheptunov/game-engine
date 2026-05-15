package ui;

import harness.SuiteRunner;
import harness.Test;

import java.awt.event.MouseEvent;
import java.io.StringReader;
import java.util.function.Consumer;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class BindingsLoaderTest {
    @Test
    void loadsSingleBinding() {
        var actions = new ActionRegistry<Runnable>().register("foo", () -> {});
        var bindings = BindingsLoader.loadInto(new StringReader("q = foo\n"), new InputBindings(actions));
        assertEquals("foo", bindings.lookup(KeyChord.of(KeyAction.Key.LOWER_Q)).orElseThrow());
    }

    @Test
    void loadsMultipleBindings() {
        var actions = new ActionRegistry<Runnable>()
                .register("a1", () -> {})
                .register("a2", () -> {});
        var bindings = BindingsLoader.loadInto(
                new StringReader("q = a1\nctrl+shift+z = a2\n"),
                new InputBindings(actions));
        assertEquals("a1", bindings.lookup(KeyChord.of(KeyAction.Key.LOWER_Q)).orElseThrow());
        assertEquals("a2", bindings.lookup(KeyChord.ctrlShift(KeyAction.Key.LOWER_Z)).orElseThrow());
    }

    @Test
    void commentsAndBlankLinesIgnored() {
        var actions = new ActionRegistry<Runnable>().register("foo", () -> {});
        var bindings = BindingsLoader.loadInto(
                new StringReader("# comment\n\nq = foo\n# trailing\n"),
                new InputBindings(actions));
        assertEquals("foo", bindings.lookup(KeyChord.of(KeyAction.Key.LOWER_Q)).orElseThrow());
    }

    @Test
    void emptyActionIdThrows() {
        var bindings = new InputBindings(new ActionRegistry<Runnable>());
        try {
            BindingsLoader.loadInto(new StringReader("q = \n"), bindings);
            throw new RuntimeException("expected ISE");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("q"));
        }
    }

    @Test
    void loadsIntoMouseBindings() {
        var actions = new ActionRegistry<Consumer<MouseEvent>>().register("paint", e -> {});
        var bindings = BindingsLoader.loadInto(
                new StringReader("left+press+brush = paint\n"),
                new MouseBindings(actions));
        assertEquals("paint",
                bindings.lookup(MouseChord.of(MouseButton.LEFT, MouseGesture.PRESS, "brush")).orElseThrow());
    }

    @Test
    void unknownKeyThrows() {
        var bindings = new InputBindings(new ActionRegistry<Runnable>().register("foo", () -> {}));
        try {
            BindingsLoader.loadInto(new StringReader("nope = foo\n"), bindings);
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
