package engine;

import static harness.Assertions.*;

import harness.SuiteRunner;
import harness.Test;

import rendering.PixelRaster;
import rendering.RgbPacking;

import scenes.viewport.*;

import java.util.Random;

public class DisplayConverterTest {
    @Test
    void fusedOutputMatchesReferenceBytesAcrossGridsAndExposure() {
        // Equal, integer enlargement/reduction, fractional in both directions, and mixed axes.
        for (var dims :
                new int[][] {
                    {7, 5, 7, 5},
                    {7, 5, 28, 15},
                    {12, 18, 4, 6},
                    {11, 7, 17, 13},
                    {17, 13, 11, 7},
                    {3, 11, 13, 4},
                    {400, 400, 144, 90},
                    {160, 100, 144, 90}
                }) {
            int sw = dims[0], sh = dims[1], w = dims[2], h = dims[3];
            var rgb = new float[3][sh][sw];
            var random = new Random(37);
            for (var channel : rgb)
                for (var row : channel)
                    for (int x = 0; x < sw; x++) row[x] = random.nextFloat() * 50 - .01f;
            rgb[0][0][0] = Float.POSITIVE_INFINITY;
            var converter = new DisplayConverter(w, h);
            var raster = new PixelRaster(w, h);
            for (float exposure : new float[] {1, .0625f, 16}) {
                var reference = new float[3][][];
                for (int c = 0; c < 3; c++) reference[c] = Resampler.resample(rgb[c], h, w).buf();
                converter.convert(rgb, exposure);
                converter.paint(raster);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x, sy = h - 1 - y;
                        assertEquals((byte) 255, raster.alpha()[i]);
                        assertEquals(
                                DisplayMapping.encode(reference[0][sy][x], exposure),
                                raster.red()[i]);
                        assertEquals(
                                DisplayMapping.encode(reference[1][sy][x], exposure),
                                raster.green()[i]);
                        assertEquals(
                                DisplayMapping.encode(reference[2][sy][x], exposure),
                                raster.blue()[i]);
                    }
                var saved = raster.clone();
                // Simulate UI, overlay, and editor writes. Cached presentation must erase them.
                java.util.Arrays.fill(raster.alpha(), (byte) 0);
                java.util.Arrays.fill(raster.red(), (byte) 3);
                java.util.Arrays.fill(raster.green(), (byte) 4);
                java.util.Arrays.fill(raster.blue(), (byte) 5);
                converter.paint(raster);
                assertEquals(saved, raster);
            }
            // Rebuild filter geometry after a sensor resolution change.
            converter.convert(new float[3][3][2], 1);
            converter.paint(raster);
            assertEquals((byte) 0, raster.red()[0]);
        }
    }

    @Test
    void awtPackingPreservesEveryAlphaAndChannelCombination() {
        var raster = new PixelRaster(256, 256);
        var packed = new int[256 * 256];
        for (int a = 0; a < 256; a++)
            for (int c = 0; c < 256; c++) {
                int i = a * 256 + c;
                raster.alpha()[i] = (byte) a;
                raster.red()[i] = (byte) c;
                raster.green()[i] = (byte) (255 - c);
                raster.blue()[i] = (byte) ((c * 37) % 256);
            }
        RgbPacking.copy(raster, packed);
        for (int i = 0; i < packed.length; i++) {
            double a = (raster.alpha()[i] & 255) / 255.;
            int expected =
                    ((int) (a * (raster.red()[i] & 255)) << 16)
                            | ((int) (a * (raster.green()[i] & 255)) << 8)
                            | (int) (a * (raster.blue()[i] & 255));
            assertEquals(expected, packed[i]);
        }
    }

    public static void main(String[] args) {
        SuiteRunner.runThis();
    }
}
