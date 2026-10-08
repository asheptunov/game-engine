package engine.input;

import static harness.Assertions.*;

import harness.SuiteRunner;
import harness.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class EngineInputTest {
    @Test
    void keyBindingsRoundTripEscapedPlusAndEqualsAndDispatchStrictModifiers() throws Exception {
        var calls = new AtomicInteger();
        var actions =
                new ActionRegistry<Runnable>()
                        .register("plus", () -> calls.addAndGet(1))
                        .register("equal", () -> calls.addAndGet(10))
                        .register("undo", () -> calls.addAndGet(100))
                        .register("redo", () -> calls.addAndGet(1000));
        var original =
                new KeyBindings(actions)
                        .bind(KeyChord.parse("+"), "plus")
                        .bind(KeyChord.parse("="), "equal")
                        .bind(KeyChord.parse("ctrl+z"), "undo")
                        .bind(KeyChord.parse("ctrl+shift+z"), "redo");

        var saved = new StringWriter();
        BindingFiles.save(saved, original);
        assertTrue(saved.toString().contains("\\= = equal"));
        assertTrue(saved.toString().contains("+ = plus"));
        var loaded =
                BindingFiles.load(new StringReader(saved.toString()), new KeyBindings(actions))
                        .validate("keys");
        assertEquals(original.serialized(), loaded.serialized());
        var path = Path.of("out", "d1", "engine-input-roundtrip.properties");
        BindingFiles.save(path, original);
        assertEquals(
                original.serialized(),
                BindingFiles.load(path, new KeyBindings(actions)).serialized());
        java.nio.file.Files.delete(path);

        assertTrue(loaded.handle(new KeyInput(KeyCode.EQUAL, true, Modifiers.NONE)));
        assertTrue(
                loaded.handle(
                        new KeyInput(
                                KeyCode.EQUAL, true, new Modifiers(false, false, true, false))));
        assertTrue(
                loaded.handle(
                        new KeyInput(KeyCode.Z, true, new Modifiers(true, false, false, false))));
        assertTrue(
                loaded.handle(
                        new KeyInput(KeyCode.Z, true, new Modifiers(true, false, true, false))));
        assertFalse(
                loaded.handle(
                        new KeyInput(KeyCode.Z, true, new Modifiers(true, true, false, false))));
        assertFalse(
                loaded.handle(
                        new KeyInput(KeyCode.Z, false, new Modifiers(true, false, false, false))));
        assertEquals(1111, calls.get());
    }

    @Test
    void mouseBindingsDistinguishHeldRightFromShiftRight() {
        var calls = new AtomicInteger();
        ActionRegistry<Consumer<MouseInput>> actions = new ActionRegistry<>();
        actions.register("orbit", input -> calls.addAndGet(1));
        actions.register("pan", input -> calls.addAndGet(10));
        var bindings =
                new MouseBindings(actions)
                        .bind(MouseChord.parse("right+drag+viewport"), "orbit")
                        .bind(MouseChord.parse("shift+right+drag+viewport"), "pan");
        assertTrue(
                bindings.handle(
                        new MouseInput(
                                MouseButton.RIGHT,
                                MouseGesture.DRAG,
                                "VIEWPORT",
                                Modifiers.NONE,
                                2,
                                3,
                                0)));
        assertTrue(
                bindings.handle(
                        new MouseInput(
                                MouseButton.RIGHT,
                                MouseGesture.DRAG,
                                "viewport",
                                new Modifiers(false, false, true, false),
                                2,
                                3,
                                0)));
        assertEquals(11, calls.get());
    }

    @Test
    void mouseBindingsSupportHeldPhysicalKeysWithoutChangingLegacyInputs() {
        var calls = new AtomicInteger();
        var actions =
                new ActionRegistry<Consumer<MouseInput>>()
                        .register("orbit", _ -> calls.incrementAndGet())
                        .register("pan", _ -> calls.addAndGet(10));
        var bindings =
                new MouseBindings(actions)
                        .bind(MouseChord.parse("right+drag+viewport"), "orbit")
                        .bind(MouseChord.parse("key.space+right+drag+viewport"), "pan")
                        .bind(MouseChord.parse("key.q+middle+drag+viewport"), "pan");
        assertEquals(
                "key.space+right+drag+viewport",
                MouseChord.parse("key.space+right+drag+viewport").format());
        assertEquals("right+drag+space", MouseChord.parse("right+drag+space").format());
        assertTrue(
                bindings.handle(
                        new MouseInput(
                                MouseButton.RIGHT,
                                MouseGesture.DRAG,
                                "viewport",
                                Modifiers.NONE,
                                1,
                                2,
                                0)));
        assertTrue(
                bindings.handle(
                        new MouseInput(
                                MouseButton.RIGHT,
                                MouseGesture.DRAG,
                                "viewport",
                                Modifiers.NONE,
                                1,
                                2,
                                0,
                                Set.of(KeyCode.SPACE))));
        assertTrue(
                bindings.handle(
                        new MouseInput(
                                MouseButton.MIDDLE,
                                MouseGesture.DRAG,
                                "viewport",
                                Modifiers.NONE,
                                1,
                                2,
                                0,
                                Set.of(KeyCode.Q))));
        assertEquals(21, calls.get());
        expect(
                IllegalArgumentException.class,
                () -> MouseChord.parse("key.l_ctrl+right+drag+viewport"));
    }

    @Test
    void invalidMissingUnresolvedAmbiguousAndDuplicateBindingsFailClearly() {
        var missing = Path.of("out", "d1", "definitely-missing-bindings.properties");
        expect(
                IllegalStateException.class,
                () -> BindingFiles.load(missing, new KeyBindings(new ActionRegistry<>())));
        expect(
                IllegalArgumentException.class,
                () -> new ActionRegistry<Runnable>().register("bad id", () -> {}));
        expect(
                IllegalArgumentException.class,
                () ->
                        BindingFiles.load(
                                new StringReader("q = bad id\n"),
                                new KeyBindings(new ActionRegistry<>())));

        var unresolved =
                BindingFiles.load(
                        new StringReader("q = absent\n"), new KeyBindings(new ActionRegistry<>()));
        expect(IllegalStateException.class, () -> unresolved.validate("test bindings"));
        expect(IllegalArgumentException.class, () -> MouseChord.parse("left+right+drag"));
        expect(IllegalArgumentException.class, () -> MouseChord.parse("press+drag"));
        expect(
                IllegalArgumentException.class,
                () -> MouseChord.of(MouseButton.RIGHT, MouseGesture.DRAG, "bad+mode"));
        expect(
                IllegalArgumentException.class,
                () ->
                        BindingFiles.load(
                                new StringReader("ctrl+shift+z = redo\nshift+ctrl+z = undo\n"),
                                new KeyBindings(new ActionRegistry<>())));
    }

    private static void expect(Class<? extends Throwable> type, Runnable body) {
        try {
            body.run();
            throw new RuntimeException("Expected " + type.getSimpleName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) {
                if (error instanceof RuntimeException runtime) throw runtime;
                if (error instanceof Error fatal) throw fatal;
                throw new RuntimeException(error);
            }
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
