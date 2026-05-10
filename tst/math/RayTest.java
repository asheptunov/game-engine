package math;

import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;

public class RayTest {
    @Test
    void atZero() {
        var r = new Ray(new Vec3(1, 2, 3), new Vec3(0, 0, 1));
        assertEquals(new Vec3(1, 2, 3), r.at(0));
    }

    @Test
    void atPositive() {
        var r = new Ray(new Vec3(1, 2, 3), new Vec3(0, 0, 1));
        assertEquals(new Vec3(1, 2, 8), r.at(5));
    }

    @Test
    void atNegative() {
        var r = new Ray(new Vec3(1, 2, 3), new Vec3(0, 0, 1));
        assertEquals(new Vec3(1, 2, 1), r.at(-2));
    }

    @Test
    void atWithNonUnitDirection() {
        var r = new Ray(Vec3.ZERO, new Vec3(2, 0, 0));
        assertEquals(new Vec3(6, 0, 0), r.at(3));
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
