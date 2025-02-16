package harness;

import java.util.Objects;

public class Assertions {
    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new RuntimeException("Expected\n%s\nbut got\n%s".formatted(expected, actual));
        }
    }

    public static void assertTrue(boolean b) {
        assertEquals(true, b);
    }

    public static void assertFalse(boolean b) {
        assertEquals(false, b);
    }
}
