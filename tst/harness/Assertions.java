package harness;

import java.util.Objects;

public class Assertions {
    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new RuntimeException("Expected equal objects, but got two non-equal ones:\nexpected:\n%s\nactual:\n%s"
                    .formatted(expected, actual));
        }
    }

    public static void assertNotEquals(Object expected, Object actual) {
        if (Objects.deepEquals(expected, actual)) {
            throw new RuntimeException("Expected non-equal objects, but go two that equal:\nexpected:\n%s\nactual:\n%s"
                    .formatted(expected, actual));
        }
    }

    public static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new RuntimeException(
                    "Expected two of the same instance, but got two different ones:\nexpected:\n%s\nactual:\n%s"
                            .formatted(expected, actual));
        }
    }

    public static void assertNotSame(Object expected, Object actual) {
        if (expected == actual) {
            throw new RuntimeException("Expected different instances, but got two of\n" + actual);
        }
    }

    public static void assertTrue(boolean b) {
        if (!b) {
            throw new RuntimeException("Expected true, but got false");
        }
    }

    public static void assertFalse(boolean b) {
        if (b) {
            throw new RuntimeException("Expected false, but got true");
        }
    }

    public static void assertNull(Object actual) {
        if (actual != null) {
            throw new RuntimeException("Expected null, but got\n" + actual);
        }
    }

    public static void assertNotNull(Object actual) {
        if (actual == null) {
            throw new RuntimeException("Expected non-null, but got null");
        }
    }

    public static <T> T assertInstanceOf(Class<T> klass, Object actual) {
        if (!klass.isInstance(actual)) {
            throw new RuntimeException("Expected\n%s\nto be an instance of %s, but it wasn't".formatted(actual, klass));
        }
        //noinspection unchecked
        return (T) actual;
    }
}
