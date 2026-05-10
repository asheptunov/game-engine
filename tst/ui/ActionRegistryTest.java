package ui;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class ActionRegistryTest {
    @Test
    void getRegisteredReturnsAction() {
        var r = new ActionRegistry();
        var fired = new boolean[]{false};
        r.register("foo", () -> fired[0] = true);
        var fn = r.get("foo");
        assertTrue(fn.isPresent());
        fn.get().run();
        assertTrue(fired[0]);
    }

    @Test
    void getMissingReturnsEmpty() {
        var r = new ActionRegistry();
        assertEquals(true, r.get("nope").isEmpty());
    }

    @Test
    void duplicateRegisterThrows() {
        var r = new ActionRegistry().register("x", () -> {});
        try {
            r.register("x", () -> {});
            throw new RuntimeException("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void idsAreReturnedInInsertionOrder() {
        var r = new ActionRegistry()
                .register("a", () -> {})
                .register("c", () -> {})
                .register("b", () -> {});
        assertEquals(new String[]{"a", "c", "b"}, r.ids().toArray(new String[0]));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
