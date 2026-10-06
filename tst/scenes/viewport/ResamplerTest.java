package engine;

import scenes.viewport.*;
import harness.SuiteRunner;
import harness.Test;

import static harness.Assertions.assertEquals;
import static harness.Assertions.assertTrue;

public class ResamplerTest {
    private static final float EPS = 1e-5f;

    /** sensor &lt; display, integer ratio: every output pixel falls fully inside one input pixel. */
    @Test
    void upscaleIsNearestNeighborAtIntegerRatio() {
        var in = new float[][] {
                {1, 2},
                {3, 4},
        };
        var result = Resampler.resample(in, 4, 4);
        var expected = new float[][] {
                {1, 1, 2, 2},
                {1, 1, 2, 2},
                {3, 3, 4, 4},
                {3, 3, 4, 4},
        };
        assertGridsEqual(expected, result.buf());
        assertTrue(Math.abs(result.max() - 4f) < EPS);
    }

    /** sensor == display: bit-for-bit copy. */
    @Test
    void oneToOneIsCopy() {
        var in = new float[][] {
                {0.1f, 0.2f, 0.3f},
                {0.4f, 0.5f, 0.6f},
                {0.7f, 0.8f, 0.9f},
        };
        var result = Resampler.resample(in, 3, 3);
        assertGridsEqual(in, result.buf());
        assertTrue(Math.abs(result.max() - 0.9f) < EPS);
    }

    /** sensor &gt; display, integer ratio: each output pixel is the box-average of an N×N block. */
    @Test
    void downscaleIsBoxAverageAtIntegerRatio() {
        var in = new float[][] {
                {1, 2, 3, 4},
                {5, 6, 7, 8},
                {9, 10, 11, 12},
                {13, 14, 15, 16},
        };
        var result = Resampler.resample(in, 2, 2);
        var expected = new float[][] {
                {(1 + 2 + 5 + 6) / 4f,    (3 + 4 + 7 + 8) / 4f},
                {(9 + 10 + 13 + 14) / 4f, (11 + 12 + 15 + 16) / 4f},
        };
        assertGridsEqual(expected, result.buf());
        assertTrue(Math.abs(result.max() - (11 + 12 + 15 + 16) / 4f) < EPS);
    }

    /** Non-integer downscale (3 → 2): boundary input pixels contribute fractional weight. */
    @Test
    void downscaleAtNonIntegerRatioIsAreaWeighted() {
        // 1D version (single row) is enough to verify the weighting math.
        // Input width 3, output width 2 → sxPerDx = 1.5.
        // Output pixel 0 spans input [0.0, 1.5): full input[0] + half of input[1] = (10 + 0.5*20)/1.5 = 20/1.5 ≈ 13.333
        // Output pixel 1 spans input [1.5, 3.0): half of input[1] + full input[2] = (0.5*20 + 30)/1.5 = 40/1.5 ≈ 26.667
        var in = new float[][] {{10, 20, 30}};
        var result = Resampler.resample(in, 1, 2);
        assertTrue(Math.abs(result.buf()[0][0] - 20f / 1.5f) < EPS);
        assertTrue(Math.abs(result.buf()[0][1] - 40f / 1.5f) < EPS);
        assertTrue(Math.abs(result.max() - 40f / 1.5f) < EPS);
    }

    private static void assertGridsEqual(float[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int y = 0; y < expected.length; y++) {
            assertEquals(expected[y].length, actual[y].length);
            for (int x = 0; x < expected[y].length; x++) {
                assertTrue(Math.abs(expected[y][x] - actual[y][x]) < EPS);
            }
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
