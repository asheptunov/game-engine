package di;

import harness.SuiteRunner;
import harness.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertInstanceOf;
import static harness.Assertions.assertNull;

public class GenericTypeTest {
    @Test
    void genericNestedParameterizedType() {
        var pt = assertInstanceOf(ParameterizedType.class,
                new GenericType<Map<String, List<String>>>() {}.getType());
        assertEquals(Map.class, pt.getRawType());
        assertNull(pt.getOwnerType());
        assertEquals(2, pt.getActualTypeArguments().length);
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
        pt = assertInstanceOf(ParameterizedType.class, pt.getActualTypeArguments()[1]);
        assertEquals(List.class, pt.getRawType());
        assertNull(pt.getOwnerType());
        assertEquals(new Type[]{String.class}, pt.getActualTypeArguments());
    }

    @Test
    void genericParameterizedType() {
        var pt = assertInstanceOf(ParameterizedType.class,
                new GenericType<List<String>>() {}.getType());
        assertEquals(List.class, pt.getRawType());
        assertNull(pt.getOwnerType());
        assertEquals(new Type[]{String.class}, pt.getActualTypeArguments());
    }

    @Test
    void genericNonParameterizedType() {
        assertEquals(String.class, new GenericType<String>() {}.getType());
    }

    @SuppressWarnings("rawtypes")
    @Test
    void rawType() {
        assertEquals(Object.class, new GenericType() {}.getType());
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
