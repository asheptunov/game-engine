package engine;

/** Display-only HDR light spread. OPTICAL redistributes energy; SIMPLE adds bright highlights. */
public enum Bloom {
    OFF,
    SIMPLE,
    OPTICAL;

    /** Reusable, single-owner quarter-resolution filter; never modifies the input radiance. */
    public static final class Filter {
        private float[] first = new float[0];
        private float[] second = new float[0];
        // Sigma remains three low-resolution pixels. Beyond five sigma the solar halo
        // reaches display black before the separable filter's square support ends.
        private static final int RADIUS = 16;
        private static final float[] WEIGHTS = weights();

        private static float[] weights() {
            var result = new float[RADIUS * 2 + 1];
            float sum = 0;
            for (int i = -RADIUS; i <= RADIUS; i++) {
                result[i + RADIUS] = (float) Math.exp(-i * i / 18.);
                sum += result[i + RADIUS];
            }
            for (int i = 0; i < result.length; i++) {
                result[i] /= sum;
            }
            return result;
        }

        public void copy(
                float[] rgb, int width, int height, int[] destination, float exposure, Bloom mode) {
            copy(new Interleaved(rgb, width, height), destination, exposure, mode);
        }

        private record Interleaved(float[] rgb, int width, int height) implements RgbPixels {
            public float value(int channel, int x, int y) {
                return rgb[(y * width + x) * 3 + channel];
            }

            public void copyTo(float[][][] destination) {
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            destination[c][y][x] = value(c, x, y);
                        }
                    }
                }
            }
        }

        public void copy(RgbPixels source, int[] destination, float exposure, Bloom mode) {
            int width = source.width();
            int height = source.height();
            if (destination.length != width * height) {
                throw new IllegalArgumentException("Display size mismatch");
            }
            if (mode == OFF) {
                // Read raw values explicitly; a backend's packed cache may include another mode.
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        destination[(height - 1 - y) * width + x] =
                                DisplayMapping.pack(
                                        source.value(0, x, y),
                                        source.value(1, x, y),
                                        source.value(2, x, y),
                                        exposure);
                    }
                }
                return;
            }
            int w = (width + 3) / 4;
            int h = (height + 3) / 4;
            if (first.length != w * h * 3) {
                first = new float[w * h * 3];
                second = new float[first.length];
            }
            downsample(source, exposure, mode, w, h);
            blur(first, second, w, h, true);
            blur(second, first, w, h, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    destination[(height - 1 - y) * width + x] =
                            DisplayMapping.pack(
                                    channel(source, mode, w, h, 0, x, y),
                                    channel(source, mode, w, h, 1, x, y),
                                    channel(source, mode, w, h, 2, x, y),
                                    exposure);
                }
            }
        }

        private void downsample(RgbPixels source, float exposure, Bloom mode, int w, int h) {
            for (int pixel = 0; pixel < w * h; pixel++) {
                int x = pixel % w * 4;
                int y = pixel / w * 4;
                for (int c = 0; c < 3; c++) {
                    first[pixel * 3 + c] = average(source, exposure, mode, x, y, c);
                }
            }
        }

        private static float average(
                RgbPixels source, float exposure, Bloom mode, int x, int y, int c) {
            float sum = 0;
            int right = Math.min(source.width(), x + 4);
            int top = Math.min(source.height(), y + 4);
            for (int sy = y; sy < top; sy++) {
                for (int sx = x; sx < right; sx++) {
                    float value = Math.max(0, source.value(c, sx, sy));
                    sum += mode == SIMPLE ? Math.max(0, value - 1 / exposure) : value;
                }
            }
            return sum / ((right - x) * (top - y));
        }

        private static void blur(float[] source, float[] target, int w, int h, boolean horizontal) {
            for (int pixel = 0; pixel < w * h; pixel++) {
                int x = pixel % w;
                int y = pixel / w;
                for (int c = 0; c < 3; c++) {
                    float sum = 0;
                    for (int i = -RADIUS; i <= RADIUS; i++) {
                        int sx = horizontal ? Math.clamp(x + i, 0, w - 1) : x;
                        int sy = horizontal ? y : Math.clamp(y + i, 0, h - 1);
                        sum += source[(sy * w + sx) * 3 + c] * WEIGHTS[i + RADIUS];
                    }
                    target[pixel * 3 + c] = sum;
                }
            }
        }

        @SuppressWarnings(
                "PMD.ExcessiveParameterList") // Scalar image coordinates in an allocation-free
        // loop.
        private float channel(RgbPixels source, Bloom mode, int w, int h, int c, int x, int y) {
            float px = Math.clamp((x + .5f) / 4 - .5f, 0, w - 1);
            float py = Math.clamp((y + .5f) / 4 - .5f, 0, h - 1);
            int ix = (int) px;
            int iy = (int) py;
            float u = px - ix;
            float v = py - iy;
            int right = Math.min(ix + 1, w - 1);
            int top = Math.min(iy + 1, h - 1);
            float low =
                    first[(iy * w + ix) * 3 + c] * (1 - u) + first[(iy * w + right) * 3 + c] * u;
            float high =
                    first[(top * w + ix) * 3 + c] * (1 - u) + first[(top * w + right) * 3 + c] * u;
            float spread = low * (1 - v) + high * v;
            return mode == OPTICAL
                    ? .94f * source.value(c, x, y) + .06f * spread
                    : source.value(c, x, y) + .12f * spread;
        }
    }
}
