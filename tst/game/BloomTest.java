package game;

import static harness.Assertions.assertTrue;

import engine.Bloom;
import engine.DisplayMapping;
import engine.RenderSettings;

import harness.SuiteRunner;
import harness.Test;

public final class BloomTest {
    public static void main(String[] args) {
        SuiteRunner.runThis();
    }

    @Test
    void opticalPreservesUniformFieldAndOffPreservesBytes() {
        var filter = new Bloom.Filter();
        for (int width : new int[] {64, 67, 128}) {
            int height = 65;
            var source = new float[width * height * 3];
            java.util.Arrays.fill(source, 2);
            var result = new int[width * height];
            for (var mode : new Bloom[] {Bloom.OFF, Bloom.OPTICAL}) {
                filter.copy(source, width, height, result, 1, mode);
                for (int pixel : result) {
                    assertTrue(pixel == DisplayMapping.pack(2, 2, 2, 1));
                }
            }
            java.util.Arrays.fill(source, 0);
            filter.copy(source, width, height, result, 1, Bloom.OPTICAL);
            for (int pixel : result) {
                assertTrue(pixel == 0);
            }
        }
    }

    @Test
    void brightSourceSpreadsWithoutChangingRawRadiance() {
        int width = 128;
        var raw = new float[width * width * 3];
        for (int y = 60; y < 68; y++) {
            for (int x = 60; x < 68; x++) {
                for (int c = 0; c < 3; c++) {
                    raw[(y * width + x) * 3 + c] = 50;
                }
            }
        }
        var original = raw.clone();
        var filter = new Bloom.Filter();
        var result = new int[width * width];
        for (var mode : new Bloom[] {Bloom.SIMPLE, Bloom.OPTICAL}) {
            filter.copy(raw, width, width, result, 1, mode);
            assertTrue(result[64 * width + 78] > 0);
            assertTrue(result[0] == 0);
            assertTrue(java.util.Arrays.equals(original, raw));
        }
        filter.copy(raw, width, width, result, 1, Bloom.OFF);
        assertTrue(result[64 * width + 78] == 0);
    }

    @Test
    void solarHighlightHasRoundTailsBeyondTheFormerSquareBoundary() {
        int width = 256;
        var raw = new float[width * width * 3];
        for (int y = 128; y < 132; y++) {
            for (int x = 128; x < 132; x++) {
                for (int c = 0; c < 3; c++) {
                    raw[(y * width + x) * 3 + c] = 100000;
                }
            }
        }
        var result = new int[width * width];
        for (var mode : new Bloom[] {Bloom.SIMPLE, Bloom.OPTICAL}) {
            new Bloom.Filter().copy(raw, width, width, result, 1, mode);
            int axial = result[(width - 1 - 129) * width + 169] & 255;
            int diagonal = result[(width - 1 - 157) * width + 157] & 255;
            assertTrue(axial > 10);
            assertTrue(Math.abs(axial - diagonal) < 10);
            assertTrue((result[(width - 1 - 129) * width + 199] & 255) <= 1);
        }
    }

    @Test
    void editsAndCaptureRetainBloom() throws Exception {
        var settings =
                RenderSettings.defaults()
                        .withBloom(Bloom.OPTICAL)
                        .withPathDepth(3)
                        .withFoveated(true);
        assertTrue(settings.bloom() == Bloom.OPTICAL);
        var file = java.nio.file.Files.createTempFile("bloom", ".properties");
        try {
            CaptureCodec.save(file, settings);
            assertTrue(settings.equals(CaptureCodec.load(file, RenderSettings.class)));
            var text =
                    java.nio.file.Files.readString(file).replaceAll("(?m)^data\\.bloom=.*\\R?", "");
            java.nio.file.Files.writeString(file, text);
            assertTrue(CaptureCodec.load(file, RenderSettings.class).bloom() == Bloom.OFF);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
